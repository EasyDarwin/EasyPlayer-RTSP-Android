// Replaces the legacy codec/YUV JNI libraries with a single, source-built library.
#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <memory>
#include <vector>
#include <unistd.h>
extern "C" {
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>
#include <libavutil/audio_fifo.h>
#include <libavutil/imgutils.h>
#include <libavutil/opt.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
}

namespace {
constexpr const char* TAG = "EasyPlayerFFmpeg";
int error(int code, const char* operation) {
    char message[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(code, message, sizeof(message));
    __android_log_print(ANDROID_LOG_ERROR, TAG, "%s: %s (%d)", operation, message, code);
    return code;
}
void invalid(JNIEnv* env, const char* message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), message);
}
bool range(JNIEnv* env, jbyteArray data, int offset, int length) {
    if (!data || offset < 0 || length < 0 || offset > env->GetArrayLength(data) - length) {
        invalid(env, "Invalid byte array range");
        return false;
    }
    return true;
}
AVPacket* packet(JNIEnv* env, jbyteArray data, int offset, int length) {
    if (!range(env, data, offset, length)) return nullptr;
    AVPacket* p = av_packet_alloc();
    if (!p || av_new_packet(p, length) < 0) {
        av_packet_free(&p);
        return nullptr;
    }
    env->GetByteArrayRegion(data, offset, length, reinterpret_cast<jbyte*>(p->data));
    return p;
}
template<typename T> T* handle(jlong value) { return reinterpret_cast<T*>(static_cast<intptr_t>(value)); }
template<typename T> jlong handle(T* value) { return static_cast<jlong>(reinterpret_cast<intptr_t>(value)); }
struct Decoder {
    AVCodecContext* codec = nullptr;
    AVFrame* frame = av_frame_alloc();
    SwrContext* resampler = nullptr;
    SwsContext* converter = nullptr;
    SwsContext* renderer = nullptr;
    ANativeWindow* window = nullptr;
    int sampleRate = 0;
    int channels = 0;
    int frames = 0;
    ~Decoder() {
        if (window) ANativeWindow_release(window);
        sws_freeContext(converter);
        sws_freeContext(renderer);
        swr_free(&resampler);
        av_frame_free(&frame);
        avcodec_free_context(&codec);
    }
};
std::unique_ptr<Decoder> decoder(AVCodecID id) {
    const AVCodec* codec = avcodec_find_decoder(id);
    if (!codec) return nullptr;
    auto d = std::make_unique<Decoder>();
    d->codec = avcodec_alloc_context3(codec);
    if (!d->codec || !d->frame) return nullptr;
    return d;
}
int render(Decoder* d, const AVFrame* frame) {
    if (!d->window) return 0;
    if (ANativeWindow_getWidth(d->window) != frame->width || ANativeWindow_getHeight(d->window) != frame->height ||
        ANativeWindow_getFormat(d->window) != WINDOW_FORMAT_RGBA_8888) {
        if (ANativeWindow_setBuffersGeometry(d->window, frame->width, frame->height, WINDOW_FORMAT_RGBA_8888) < 0)
            return AVERROR(EIO);
    }
    d->renderer = sws_getCachedContext(d->renderer, frame->width, frame->height,
        static_cast<AVPixelFormat>(frame->format), frame->width, frame->height, AV_PIX_FMT_RGBA,
        SWS_BILINEAR, nullptr, nullptr, nullptr);
    if (!d->renderer) return AVERROR(ENOMEM);
    // Respect the stream's full/limited range and matrix rather than assuming BT.601.
    int matrix = frame->colorspace == AVCOL_SPC_BT709 ? SWS_CS_ITU709 : SWS_CS_ITU601;
    const int* coefficients = sws_getCoefficients(matrix);
    sws_setColorspaceDetails(d->renderer, coefficients, frame->color_range == AVCOL_RANGE_JPEG,
                            coefficients, 1, 0, 1 << 16, 1 << 16);
    ANativeWindow_Buffer output;
    if (ANativeWindow_lock(d->window, &output, nullptr) < 0) return AVERROR(EIO);
    uint8_t* planes[4] = {static_cast<uint8_t*>(output.bits), nullptr, nullptr, nullptr};
    int strides[4] = {output.stride * 4, 0, 0, 0};
    int result = sws_scale(d->renderer, frame->data, frame->linesize, 0, frame->height, planes, strides);
    int posted = ANativeWindow_unlockAndPost(d->window);
    return result > 0 && posted == 0 ? 0 : AVERROR(EIO);
}
int packedI420(Decoder* d, const AVFrame* frame, uint8_t** output) {
    int size = av_image_get_buffer_size(AV_PIX_FMT_YUV420P, frame->width, frame->height, 1);
    if (size < 0) return size;
    *output = static_cast<uint8_t*>(av_malloc(size));
    if (!*output) return AVERROR(ENOMEM);
    uint8_t* planes[4];
    int strides[4];
    av_image_fill_arrays(planes, strides, *output, AV_PIX_FMT_YUV420P, frame->width, frame->height, 1);
    if (frame->format == AV_PIX_FMT_YUV420P || frame->format == AV_PIX_FMT_YUVJ420P) {
        av_image_copy(planes, strides, const_cast<const uint8_t**>(frame->data), frame->linesize,
                      AV_PIX_FMT_YUV420P, frame->width, frame->height);
    } else {
        d->converter = sws_getCachedContext(d->converter, frame->width, frame->height,
            static_cast<AVPixelFormat>(frame->format), frame->width, frame->height, AV_PIX_FMT_YUV420P,
            SWS_BILINEAR, nullptr, nullptr, nullptr);
        if (!d->converter || sws_scale(d->converter, frame->data, frame->linesize, 0, frame->height, planes, strides) <= 0) {
            av_freep(output);
            return AVERROR(EINVAL);
        }
    }
    return size;
}
// Drain every frame. EAGAIN is normal: a packet may produce zero, one or several frames.
template<typename Consume> int decode(Decoder* d, AVPacket* p, Consume consume) {
    auto drain = [&]() {
        int result;
        while ((result = avcodec_receive_frame(d->codec, d->frame)) >= 0) {
            result = consume(d->frame);
            av_frame_unref(d->frame);
            if (result < 0) return result;
            if (++d->frames == 1 || d->frames % 250 == 0)
                __android_log_print(ANDROID_LOG_INFO, TAG, "%s decoded frames=%d", d->codec->codec->name, d->frames);
        }
        return result == AVERROR(EAGAIN) || result == AVERROR_EOF ? 0 : result;
    };
    int result = avcodec_send_packet(d->codec, p);
    if (result == AVERROR(EAGAIN)) {
        result = drain();
        if (result < 0) return result;
        result = avcodec_send_packet(d->codec, p);
    }
    return result < 0 ? result : drain();
}
int decodeVideo(JNIEnv* env, Decoder* d, jbyteArray data, jint offset, jint length,
                jintArray dimensions, uint8_t** output) {
    if (!d || !dimensions || env->GetArrayLength(dimensions) < 2) return AVERROR(EINVAL);
    AVPacket* p = packet(env, data, offset, length);
    if (!p) return AVERROR(EINVAL);
    int bytes = 0;
    int result = decode(d, p, [&](AVFrame* frame) {
        int status = render(d, frame);
        if (status < 0) return status;
        jint size[2] = {frame->width, frame->height};
        env->SetIntArrayRegion(dimensions, 0, 2, size);
        if (output) {
            av_freep(output);
            bytes = packedI420(d, frame, output);
            if (bytes < 0) return bytes;
        }
        return 0;
    });
    av_packet_free(&p);
    if (result < 0) { if (output) av_freep(output); return error(result, "video decode"); }
    return bytes;
}

struct Muxer {
    AVFormatContext* format = nullptr;
    AVCodecContext* encoder = nullptr;
    AVStream* video = nullptr;
    AVStream* audio = nullptr;
    SwrContext* resampler = nullptr;
    AVAudioFifo* fifo = nullptr;
    int64_t originMs = AV_NOPTS_VALUE;
    int64_t audioPts = AV_NOPTS_VALUE;
    int64_t lastVideoDts = AV_NOPTS_VALUE;
    bool header = false;
    ~Muxer() {
        av_audio_fifo_free(fifo);
        swr_free(&resampler);
        avcodec_free_context(&encoder);
        if (format) { if (format->pb) avio_closep(&format->pb); avformat_free_context(format); }
    }
    int drainAudio() {
        AVPacket* p = av_packet_alloc();
        if (!p) return AVERROR(ENOMEM);
        int result;
        while ((result = avcodec_receive_packet(encoder, p)) >= 0) {
            av_packet_rescale_ts(p, encoder->time_base, audio->time_base);
            p->stream_index = audio->index;
            result = av_interleaved_write_frame(format, p);
            av_packet_unref(p);
            if (result < 0) break;
        }
        av_packet_free(&p);
        return result == AVERROR(EAGAIN) || result == AVERROR_EOF ? 0 : result;
    }
    int encodeAudio(bool finish) {
        if (!encoder) return 0;
        const int block = encoder->frame_size;
        while (av_audio_fifo_size(fifo) >= block || (finish && av_audio_fifo_size(fifo) > 0)) {
            int count = std::min(av_audio_fifo_size(fifo), block);
            AVFrame* frame = av_frame_alloc();
            if (!frame) return AVERROR(ENOMEM);
            frame->nb_samples = finish && count < block && (encoder->codec->capabilities & AV_CODEC_CAP_SMALL_LAST_FRAME)
                ? count : block;
            frame->format = encoder->sample_fmt;
            frame->sample_rate = encoder->sample_rate;
            int result = av_channel_layout_copy(&frame->ch_layout, &encoder->ch_layout);
            if (result >= 0) result = av_frame_get_buffer(frame, 0);
            if (result >= 0) {
                av_samples_set_silence(frame->data, 0, frame->nb_samples, encoder->ch_layout.nb_channels, encoder->sample_fmt);
                result = av_audio_fifo_read(fifo, reinterpret_cast<void**>(frame->data), count);
                if (result == count) {
                    frame->pts = audioPts;
                    audioPts += frame->nb_samples;
                    result = avcodec_send_frame(encoder, frame);
                } else result = AVERROR(EIO);
            }
            av_frame_free(&frame);
            if (result < 0) return result;
            result = drainAudio();
            if (result < 0) return result;
        }
        return 0;
    }
};
jfieldID muxerField(JNIEnv* env, jobject object) {
    jclass cls = env->GetObjectClass(object);
    jfieldID field = env->GetFieldID(cls, "ctx", "J");
    env->DeleteLocalRef(cls);
    return field;
}
Muxer* muxer(JNIEnv* env, jobject object) { return handle<Muxer>(env->GetLongField(object, muxerField(env, object))); }
bool keyframe(const uint8_t* data, int size, AVCodecID codec) {
    for (int i = 0; i + 4 < size; ++i) {
        if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
            int type = codec == AV_CODEC_ID_H264 ? data[i + 3] & 31 : (data[i + 3] >> 1) & 63;
            if ((codec == AV_CODEC_ID_H264 && type == 5) || (codec == AV_CODEC_ID_HEVC && type >= 16 && type <= 21)) return true;
        }
    }
    return false;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_org_easydarwin_audio_AudioCodec_create(
    JNIEnv*, jclass, jint codec, jint rate, jint channels, jint bits) {
    AVCodecID id;
    switch (codec) {
        case 0x10006: id = AV_CODEC_ID_PCM_MULAW; break;
        case 0x10007: id = AV_CODEC_ID_PCM_ALAW; break;
        case 0x1100b: id = AV_CODEC_ID_ADPCM_G726; break;
        case 0x15002: id = AV_CODEC_ID_AAC; break;
        default: return 0;
    }
    if (rate <= 0 || channels < 1 || channels > 2) return 0;
    auto d = decoder(id);
    if (!d) return 0;
    d->sampleRate = rate;
    d->channels = channels;
    d->codec->sample_rate = rate;
    av_channel_layout_default(&d->codec->ch_layout, channels);
    if (id == AV_CODEC_ID_ADPCM_G726) {
        int codedBits = bits >= 2 && bits <= 5 ? bits : 4;
        d->codec->bits_per_coded_sample = codedBits;
        d->codec->bit_rate = rate * codedBits;
    }
    if (id == AV_CODEC_ID_AAC) {
        const int rates[] = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};
        int index = 0;
        while (index < 13 && rates[index] != rate) ++index;
        if (index == 13) return 0;
        d->codec->extradata = static_cast<uint8_t*>(av_mallocz(2 + AV_INPUT_BUFFER_PADDING_SIZE));
        if (!d->codec->extradata) return 0;
        d->codec->extradata_size = 2;
        d->codec->extradata[0] = (2 << 3) | (index >> 1);
        d->codec->extradata[1] = ((index & 1) << 7) | (channels << 3);
    }
    if (avcodec_open2(d->codec, d->codec->codec, nullptr) < 0) return 0;
    return handle(d.release());
}
extern "C" JNIEXPORT jint JNICALL Java_org_easydarwin_audio_AudioCodec_decode(
    JNIEnv* env, jclass, jlong value, jbyteArray input, jint offset, jint length, jbyteArray pcm, jintArray outLen) {
    Decoder* d = handle<Decoder>(value);
    if (!d || !pcm || !outLen || env->GetArrayLength(outLen) < 1) return AVERROR(EINVAL);
    jint capacity;
    env->GetIntArrayRegion(outLen, 0, 1, &capacity);
    capacity = std::min(capacity, env->GetArrayLength(pcm));
    jint zero = 0;
    env->SetIntArrayRegion(outLen, 0, 1, &zero);
    AVPacket* p = packet(env, input, offset, length);
    if (!p) return AVERROR(EINVAL);
    std::vector<uint8_t> output;
    int result = decode(d, p, [&](AVFrame* frame) {
        if (!d->resampler) {
            AVChannelLayout layout;
            av_channel_layout_default(&layout, d->channels);
            int status = swr_alloc_set_opts2(&d->resampler, &layout, AV_SAMPLE_FMT_S16, d->sampleRate,
                &frame->ch_layout, static_cast<AVSampleFormat>(frame->format), frame->sample_rate, 0, nullptr);
            av_channel_layout_uninit(&layout);
            if (status < 0) return status;
            if ((status = swr_init(d->resampler)) < 0) return status;
        }
        int samples = swr_get_out_samples(d->resampler, frame->nb_samples);
        int64_t bytes = static_cast<int64_t>(samples) * d->channels * 2;
        if (bytes > INT32_MAX || bytes < 0) return AVERROR(EINVAL);
        size_t start = output.size();
        output.resize(start + bytes);
        uint8_t* dest[] = {output.data() + start};
        int count = swr_convert(d->resampler, dest, samples, const_cast<const uint8_t**>(frame->extended_data), frame->nb_samples);
        if (count < 0) return count;
        output.resize(start + count * d->channels * 2);
        return 0;
    });
    av_packet_free(&p);
    if (result < 0) return error(result, "audio decode");
    if (capacity < 0 || output.size() > static_cast<size_t>(capacity)) return error(AVERROR(ENOSPC), "PCM output buffer");
    jint count = static_cast<jint>(output.size());
    env->SetByteArrayRegion(pcm, 0, count, reinterpret_cast<const jbyte*>(output.data()));
    env->SetIntArrayRegion(outLen, 0, 1, &count);
    return 0;
}
extern "C" JNIEXPORT void JNICALL Java_org_easydarwin_audio_AudioCodec_close(JNIEnv*, jclass, jlong value) {
    delete handle<Decoder>(value);
}
extern "C" JNIEXPORT jlong JNICALL Java_org_easydarwin_video_VideoCodec_create(JNIEnv* env, jobject, jobject surface, jint codec) {
    if (codec != 0 && codec != 1) return 0;
    auto d = decoder(codec == 0 ? AV_CODEC_ID_H264 : AV_CODEC_ID_HEVC);
    if (!d) return 0;
    d->codec->thread_count = 4;
    d->codec->thread_type = FF_THREAD_SLICE;
    if (avcodec_open2(d->codec, d->codec->codec, nullptr) < 0) return 0;
    if (surface) {
        d->window = ANativeWindow_fromSurface(env, surface);
        if (!d->window) return 0;
    }
    return handle(d.release());
}
extern "C" JNIEXPORT void JNICALL Java_org_easydarwin_video_VideoCodec_close(JNIEnv*, jobject, jlong value) {
    delete handle<Decoder>(value);
}
extern "C" JNIEXPORT jint JNICALL Java_org_easydarwin_video_VideoCodec_decode(
    JNIEnv* env, jobject, jlong value, jbyteArray input, jint offset, jint length, jintArray size) {
    return decodeVideo(env, handle<Decoder>(value), input, offset, length, size, nullptr);
}
extern "C" JNIEXPORT jobject JNICALL Java_org_easydarwin_video_VideoCodec_decodeYUV(
    JNIEnv* env, jobject, jlong value, jbyteArray input, jint offset, jint length, jintArray size) {
    uint8_t* output = nullptr;
    int bytes = decodeVideo(env, handle<Decoder>(value), input, offset, length, size, &output);
    if (bytes <= 0) return nullptr;
    jobject buffer = env->NewDirectByteBuffer(output, bytes);
    if (!buffer) av_free(output);
    return buffer;
}
extern "C" JNIEXPORT void JNICALL Java_org_easydarwin_video_VideoCodec_releaseYUV(JNIEnv* env, jobject, jobject buffer) {
    if (buffer) av_free(env->GetDirectBufferAddress(buffer));
}
extern "C" JNIEXPORT void JNICALL Java_org_easydarwin_video_VideoCodec_decodeYUV2(
    JNIEnv* env, jobject, jlong value, jobject buffer, jint width, jint height) {
    Decoder* d = handle<Decoder>(value);
    int size = av_image_get_buffer_size(AV_PIX_FMT_YUV420P, width, height, 1);
    if (!d || !buffer || size < 0 || env->GetDirectBufferCapacity(buffer) < size) {
        invalid(env, "Invalid I420 display buffer"); return;
    }
    uint8_t* data = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
    if (!data) { invalid(env, "A direct ByteBuffer is required"); return; }
    AVFrame* frame = av_frame_alloc();
    if (!frame) return;
    frame->format = AV_PIX_FMT_YUV420P;
    frame->width = width;
    frame->height = height;
    av_image_fill_arrays(frame->data, frame->linesize, data, AV_PIX_FMT_YUV420P, width, height, 1);
    int result = render(d, frame);
    av_frame_free(&frame);
    if (result < 0) error(result, "I420 render");
}
// Only the NV12 -> I420 operation used by the current player is retained.
extern "C" JNIEXPORT void JNICALL Java_org_easydarwin_sw_JNIUtil_yuvConvert(
    JNIEnv* env, jclass, jbyteArray input, jint width, jint height, jint mode) {
    if (mode != 4 || width <= 0 || height <= 0 || width % 2 || height % 2) {
        invalid(env, "Only even-sized NV12 to I420 conversion (mode 4) is supported"); return;
    }
    int size = av_image_get_buffer_size(AV_PIX_FMT_NV12, width, height, 1);
    if (size < 0 || !range(env, input, 0, size)) return;
    std::vector<uint8_t> source(size), dest(size);
    env->GetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(source.data()));
    uint8_t* src[4]; uint8_t* dst[4]; int srcStride[4]; int dstStride[4];
    av_image_fill_arrays(src, srcStride, source.data(), AV_PIX_FMT_NV12, width, height, 1);
    av_image_fill_arrays(dst, dstStride, dest.data(), AV_PIX_FMT_YUV420P, width, height, 1);
    SwsContext* context = sws_getContext(width, height, AV_PIX_FMT_NV12, width, height, AV_PIX_FMT_YUV420P,
                                       SWS_POINT, nullptr, nullptr, nullptr);
    if (!context) return;
    int result = sws_scale(context, src, srcStride, 0, height, dst, dstStride);
    sws_freeContext(context);
    if (result == height) env->SetByteArrayRegion(input, 0, size, reinterpret_cast<jbyte*>(dest.data()));
}
extern "C" JNIEXPORT jint JNICALL Java_org_easydarwin_video_EasyMuxer2_create(
    JNIEnv* env, jobject object, jstring path, jint videoType, jint width, jint height,
    jbyteArray extra, jint sampleRate, jint channels) {
    if (muxer(env, object) || !path || !extra || width <= 0 || height <= 0 || (videoType != 0 && videoType != 1))
        return AVERROR(EINVAL);
    auto m = std::make_unique<Muxer>();
    const char* filename = env->GetStringUTFChars(path, nullptr);
    if (!filename) return AVERROR(ENOMEM);
    int result = avformat_alloc_output_context2(&m->format, nullptr, "mp4", filename);
    if (result >= 0) result = avio_open(&m->format->pb, filename, AVIO_FLAG_WRITE);
    env->ReleaseStringUTFChars(path, filename);
    if (result < 0) return error(result, "open recording");
    m->video = avformat_new_stream(m->format, nullptr);
    if (!m->video) return AVERROR(ENOMEM);
    m->video->time_base = AVRational{1, 1000};
    AVCodecParameters* params = m->video->codecpar;
    params->codec_type = AVMEDIA_TYPE_VIDEO;
    params->codec_id = videoType == 0 ? AV_CODEC_ID_H264 : AV_CODEC_ID_HEVC;
    params->width = width;
    params->height = height;
    params->codec_tag = videoType == 1 ? MKTAG('h', 'v', 'c', '1') : 0;
    params->extradata_size = env->GetArrayLength(extra);
    params->extradata = static_cast<uint8_t*>(av_mallocz(params->extradata_size + AV_INPUT_BUFFER_PADDING_SIZE));
    if (!params->extradata) return AVERROR(ENOMEM);
    env->GetByteArrayRegion(extra, 0, params->extradata_size, reinterpret_cast<jbyte*>(params->extradata));
    if (sampleRate > 0 && channels > 0) {
        if (channels > 2) return AVERROR(EINVAL);
        const AVCodec* codec = avcodec_find_encoder(AV_CODEC_ID_AAC);
        if (!codec) return AVERROR_ENCODER_NOT_FOUND;
        m->encoder = avcodec_alloc_context3(codec);
        if (!m->encoder) return AVERROR(ENOMEM);
        m->encoder->sample_rate = sampleRate;
        m->encoder->sample_fmt = AV_SAMPLE_FMT_FLTP;
        m->encoder->bit_rate = std::min(64000 * channels, sampleRate * channels * 6);
        m->encoder->time_base = AVRational{1, sampleRate};
        m->encoder->flags |= AV_CODEC_FLAG_GLOBAL_HEADER;
        av_channel_layout_default(&m->encoder->ch_layout, channels);
        if ((result = avcodec_open2(m->encoder, codec, nullptr)) < 0) return error(result, "open AAC encoder");
        m->audio = avformat_new_stream(m->format, nullptr);
        if (!m->audio) return AVERROR(ENOMEM);
        m->audio->time_base = m->encoder->time_base;
        if ((result = avcodec_parameters_from_context(m->audio->codecpar, m->encoder)) < 0) return result;
        result = swr_alloc_set_opts2(&m->resampler, &m->encoder->ch_layout, m->encoder->sample_fmt, sampleRate,
            &m->encoder->ch_layout, AV_SAMPLE_FMT_S16, sampleRate, 0, nullptr);
        if (result < 0 || (result = swr_init(m->resampler)) < 0) return result;
        m->fifo = av_audio_fifo_alloc(m->encoder->sample_fmt, channels, m->encoder->frame_size);
        if (!m->fifo) return AVERROR(ENOMEM);
    }
    if ((result = avformat_write_header(m->format, nullptr)) < 0) return error(result, "MP4 header");
    m->header = true;
    env->SetLongField(object, muxerField(env, object), handle(m.release()));
    return 0;
}
extern "C" JNIEXPORT jint JNICALL Java_org_easydarwin_video_EasyMuxer2_writeFrame(
    JNIEnv* env, jobject object, jint type, jbyteArray input, jint offset, jint length, jlong timestamp) {
    Muxer* m = muxer(env, object);
    if (!m || !range(env, input, offset, length)) return AVERROR(EINVAL);
    if (m->originMs == AV_NOPTS_VALUE) m->originMs = timestamp;
    int64_t ms = std::max<int64_t>(0, timestamp - m->originMs);
    int result;
    if (type == 0) {
        AVPacket* p = packet(env, input, offset, length);
        if (!p) return AVERROR(ENOMEM);
        p->stream_index = m->video->index;
        p->pts = p->dts = av_rescale_q(ms, AVRational{1, 1000}, m->video->time_base);
        if (m->lastVideoDts != AV_NOPTS_VALUE && p->dts <= m->lastVideoDts) p->pts = p->dts = m->lastVideoDts + 1;
        p->duration = m->lastVideoDts == AV_NOPTS_VALUE
            ? std::max<int64_t>(1, av_rescale_q(40, AVRational{1, 1000}, m->video->time_base))
            : p->dts - m->lastVideoDts;
        m->lastVideoDts = p->dts;
        if (keyframe(p->data, p->size, m->video->codecpar->codec_id)) p->flags |= AV_PKT_FLAG_KEY;
        result = av_interleaved_write_frame(m->format, p);
        av_packet_free(&p);
    } else if (type == 1 && m->encoder) {
        int channels = m->encoder->ch_layout.nb_channels;
        if (length % (channels * 2)) return AVERROR(EINVAL);
        int samples = length / (channels * 2);
        std::vector<uint8_t> pcm(length);
        env->GetByteArrayRegion(input, offset, length, reinterpret_cast<jbyte*>(pcm.data()));
        AVFrame* frame = av_frame_alloc();
        if (!frame) return AVERROR(ENOMEM);
        frame->format = m->encoder->sample_fmt;
        frame->sample_rate = m->encoder->sample_rate;
        frame->nb_samples = swr_get_out_samples(m->resampler, samples);
        result = av_channel_layout_copy(&frame->ch_layout, &m->encoder->ch_layout);
        if (result >= 0) result = av_frame_get_buffer(frame, 0);
        if (result >= 0) {
            const uint8_t* src[] = {pcm.data()};
            result = swr_convert(m->resampler, frame->data, frame->nb_samples, src, samples);
            if (result >= 0) {
                int count = result;
                int queued = av_audio_fifo_size(m->fifo);
                int64_t timestampPts = av_rescale_q(ms, AVRational{1, 1000}, m->encoder->time_base);
                if (m->audioPts == AV_NOPTS_VALUE) m->audioPts = timestampPts;
                // Preserve a real timestamp gap once the previous PCM block was consumed.
                if (!queued && timestampPts > m->audioPts + m->encoder->frame_size) m->audioPts = timestampPts;
                result = av_audio_fifo_realloc(m->fifo, queued + count);
                if (result >= 0 && av_audio_fifo_write(m->fifo, reinterpret_cast<void**>(frame->data), count) != count)
                    result = AVERROR(EIO);
            }
        }
        av_frame_free(&frame);
        if (result >= 0) result = m->encodeAudio(false);
    } else return AVERROR(EINVAL);
    return result < 0 ? error(result, "record frame") : 0;
}
extern "C" JNIEXPORT void JNICALL Java_org_easydarwin_video_EasyMuxer2_close(JNIEnv* env, jobject object) {
    std::unique_ptr<Muxer> m(muxer(env, object));
    env->SetLongField(object, muxerField(env, object), 0);
    if (!m) return;
    int result = m->encodeAudio(true);
    if (result < 0) error(result, "flush PCM");
    if (m->encoder) {
        result = avcodec_send_frame(m->encoder, nullptr);
        if (result >= 0) result = m->drainAudio();
        if (result < 0) error(result, "flush AAC");
    }
    if (m->header && (result = av_write_trailer(m->format)) < 0) error(result, "MP4 trailer");
}
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    __android_log_print(ANDROID_LOG_INFO, TAG, "FFmpeg %s; runtime page size=%ld", av_version_info(), sysconf(_SC_PAGESIZE));
    return JNI_VERSION_1_6;
}
