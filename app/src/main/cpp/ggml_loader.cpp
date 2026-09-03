// =====================================================
// ggml_loader.cpp — 极简 GGUF v3 解析 + BPE 词表加载
// 仅支持 Qwen2.5 GGUF (vocab type=BPE/SPECIAL_BPE + Q4_K_M / F16 / F32)
//
// 策略:
//   mmap 整个文件, 先解 kv 元数据, 再解 tensor info, 张量 data 直接
//   指向 mmap 区域 (不拷贝, 所以 GGUF 文件加载后不能 munmap).
//   quantization type 通过 ggml_type enum 值映射 (与 llama.cpp b5180 一致).
// =====================================================
#include "qwen_infer.h"

#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <fcntl.h>
#include <unistd.h>
#include <cstdio>
#include <cstring>
#include <cstdlib>
#include <unordered_map>
#include <utility>
#include <string>
#include <vector>
#include <algorithm>

// ---- Android log ----
#include <android/log.h>
#define LOG_TAG "qwen-loader"
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// ---- ggml type 值 (来自 llama.cpp b5180 ggml.h, 保持一致) ----
enum : int {
    GGML_TYPE_F32      = 0,
    GGML_TYPE_F16      = 1,
    GGML_TYPE_Q4_0     = 2,
    GGML_TYPE_Q4_1     = 3,
    GGML_TYPE_Q5_0     = 6,
    GGML_TYPE_Q5_1     = 7,
    GGML_TYPE_Q8_0     = 8,
    GGML_TYPE_Q8_1     = 9,
    GGML_TYPE_Q2_K     = 10,
    GGML_TYPE_Q3_K     = 11,
    GGML_TYPE_Q4_K     = 12,
    GGML_TYPE_Q4_K_M   = 13,
    GGML_TYPE_Q5_K     = 14,
    GGML_TYPE_Q5_K_M   = 15,
    GGML_TYPE_Q6_K     = 16,
    GGML_TYPE_I8       = 17,
    GGML_TYPE_I16      = 18,
    GGML_TYPE_I32      = 19,
    GGML_TYPE_COUNT,
};

static size_t ggml_type_size(int t) {
    switch (t) {
        case GGML_TYPE_F32: return 4;
        case GGML_TYPE_F16: return 2;
        case GGML_TYPE_I8 : return 1;
        case GGML_TYPE_I16: return 2;
        case GGML_TYPE_I32: return 4;
        case GGML_TYPE_Q4_0: return 2 + 16;       // 32 elems: 2B scale + 16B 4bit
        case GGML_TYPE_Q4_1: return 2 + 2 + 16;
        case GGML_TYPE_Q5_0: return 2 + 4 + 16;   // 32 elems: 2B scale + 4B high-bits + 16B low
        case GGML_TYPE_Q5_1: return 2 + 2 + 4 + 16;
        case GGML_TYPE_Q8_0: return 2 + 32;
        case GGML_TYPE_Q8_1: return 2 + 2 + 32;
        case GGML_TYPE_Q2_K: return 256/16*2 + 256/4 + 2 + 2; // rough
        case GGML_TYPE_Q3_K: return 256/8 + 256/4 + 12 + 2 + 2;
        case GGML_TYPE_Q4_K:
        case GGML_TYPE_Q4_K_M: return 2 + 2 + 12 + 256/2;    // 144B: 2B d + 2B dmin + 12B scales + 128B data (K_SCALE_SIZE=12)
        case GGML_TYPE_Q5_K:
        case GGML_TYPE_Q5_K_M: return 2 + 2 + 12 + 256/2 + 256/8;
        case GGML_TYPE_Q6_K:   return 256/2 + 256/4 + 256/16 + 2 + 2 + 2;
        default: return 4;
    }
}
static size_t ggml_blck_size(int t) {
    if (t == GGML_TYPE_Q4_K_M || t == GGML_TYPE_Q4_K || t == GGML_TYPE_Q5_K_M || t == GGML_TYPE_Q5_K ||
        t == GGML_TYPE_Q6_K || t == GGML_TYPE_Q2_K || t == GGML_TYPE_Q3_K) return 256;
    return 32;
}

static int gguf_type_size(uint32_t t) {
    // GGUF v3 metadata type sizes (NOT tensor quant types)
    switch (t) {
        case 0: return 1;   // uint8
        case 1: return 1;   // int8
        case 2: return 2;   // uint16
        case 3: return 2;   // int16
        case 4: return 4;   // uint32
        case 5: return 4;   // int32
        case 6: return 4;   // float32 ← 之前错写成 8
        case 7: return 1;   // bool    ← 之前错写成 8 (GGUF v3 spec: 1 byte)
        case 10: return 8;  // uint64
        case 11: return 8;  // int64
        case 12: return 8;  // float64
        default: return 0;  // string(8) 和 array(9) 无固定 size, 单独处理
    }
    return 0;
}

struct gguf_reader {
    uint8_t * base = nullptr;
    size_t    len  = 0;
    size_t    off  = 0;
    bool ok = true;

    bool eof(size_t need) { return off + need > len; }
    template <class T> T r() {
        if (eof(sizeof(T))) { ok = false; return T{}; }
        T v;
        memcpy(&v, base + off, sizeof(T));
        off += sizeof(T);
        return v;
    }
    uint64_t vu64() {
        // LEB128 无符号 (GGUF v3 使用 ULEB128)
        uint64_t v = 0; int sh = 0;
        for (int i=0;i<10;++i) {
            if (eof(1)) { ok = false; return 0; }
            uint8_t b = base[off++];
            v |= ((uint64_t)(b & 0x7f)) << sh;
            if (!(b & 0x80)) return v;
            sh += 7;
        }
        ok = false; return 0;
    }
    std::string r_str() {
        uint64_t n = r<uint64_t>();
        if (!ok || eof(n)) { ok=false; return {}; }
        std::string s((char*)(base+off), (size_t)n);
        off += n;
        return s;
    }
};

// =====================================================
//  QwenTokenizer 简化实现
//   解码: 查表 id -> bytes. 对字节 fallback id (>=256 且 piece len==1) 没影响.
//   编码: 按 Qwen GPT2 风格 BPE. 简化策略:
//     (1) 先用"空格保护"预处理不可靠, 退化为最粗暴 bytefallback:
//         - 遍历字符串每一个 UTF-8 字节, 先在词表里找精确匹配的长 piece,
//           找不到就拆到单字节.
// =====================================================

size_t QwenTokenizer::BytesHash::operator()(const std::string & s) const noexcept {
    // FNV-1a
    size_t h = 14695981039346656037ULL;
    for (unsigned char c : s) { h ^= c; h *= 1099511628211ULL; }
    return h;
}

int32_t QwenTokenizer::id(const std::string & piece) const {
    if (!encode_map) return -1;
    auto & m = *reinterpret_cast<std::unordered_map<std::string, int32_t>*>(encode_map);
    auto it = m.find(piece);
    return it == m.end() ? -1 : it->second;
}

void QwenTokenizer::build_encode_map() {
    if (encode_map) return;
    auto * m = new std::unordered_map<std::string, int32_t>();
    for (size_t i = 0; i < id_to_token.size(); ++i) {
        m->emplace(id_to_token[i], (int32_t)i);
    }
    encode_map = m;
    initialized = true;
}

QwenTokenizer::~QwenTokenizer() {
    if (encode_map) {
        delete reinterpret_cast<std::unordered_map<std::string, int32_t>*>(encode_map);
        encode_map = nullptr;
    }
}

QwenModel::~QwenModel() {
    if (name_map) {
        delete reinterpret_cast<std::unordered_map<std::string, QwenTensor*>*>(name_map);
        name_map = nullptr;
    }
    for (auto * t : tensors) delete t;
    tensors.clear();
    for (void * p : owned_allocs) ::free(p);
    owned_allocs.clear();
    if (mmap_ptr) {
        ::munmap(mmap_ptr, mmap_len);
        mmap_ptr = nullptr;
    }
    if (mmap_fd >= 0) {
        ::close(mmap_fd);
        mmap_fd = -1;
    }
}

void QwenModel::build_name_map() {
    if (name_map) return;
    auto * m = new std::unordered_map<std::string, QwenTensor*>();
    for (auto * t : tensors) m->emplace(t->name, t);
    name_map = m;
}

QwenTensor * QwenModel::find_tensor(const std::string & name) const {
    if (!name_map) return nullptr;
    auto & m = *reinterpret_cast<std::unordered_map<std::string, QwenTensor*>*>(name_map);
    auto it = m.find(name);
    return it == m.end() ? nullptr : it->second;
}

// =====================================================
//  meta KV 辅助
// =====================================================
struct kv_entry {
    std::string key;
    uint32_t    value_type = 0;  // 0=scalar,1=array
    uint32_t    scalar_type = 0;
    std::vector<uint8_t> scalar_bytes;
    uint32_t    arr_type = 0;
    uint64_t    arr_count = 0;
    std::vector<uint8_t> arr_bytes;
    std::vector<std::string> arr_strings;  // 对 string array
    std::string value_string;  // 对 string scalar
};

// Fix v1.3.25-fix6: 加详细逐 KV 日志 + HAINT(type=13) 支持 + 错误带上下文.
// GGUF v3 metadata value_type enum (含现代 llama.cpp 扩展的 type=13 HAINT):
//   0=UINT8, 1=INT8, 2=UINT16, 3=INT16, 4=UINT32, 5=INT32,
//   6=FLOAT32(4B), 7=BOOL(1B), 8=STRING, 9=ARRAY,
//   10=UINT64, 11=INT64, 12=FLOAT64(8B), 13=HAINT (ULEB128 变长 uint, 新版 GGUF meta/tensor 可能用到)
static char s_kv_errbuf[512];
static void consume_kv_value(gguf_reader & r, kv_entry & kv,
                             uint64_t ctx_idx, const char * ctx_key, size_t file_len)
{
    size_t off_before = r.off;
    kv.value_type = r.r<uint32_t>();
    switch (kv.value_type) {
        case 0: case 1:    // UINT8 / INT8 (1B)
            kv.scalar_type = kv.value_type;
            if (r.eof(1)) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: UINT8/INT8 want 1B but EOF (off=%llu left=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)r.off,(unsigned long long)(file_len-r.off)); r.ok=false; return; }
            kv.scalar_bytes.assign(r.base+r.off, r.base+r.off+1); r.off += 1; break;
        case 2: case 3:    // UINT16 / INT16 (2B)
            kv.scalar_type = kv.value_type;
            if (r.eof(2)) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: UINT16/INT16 want 2B but EOF (off=%llu left=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)r.off,(unsigned long long)(file_len-r.off)); r.ok=false; return; }
            kv.scalar_bytes.assign(r.base+r.off, r.base+r.off+2); r.off += 2; break;
        case 4: case 5:    // UINT32 / INT32 (4B)
            kv.scalar_type = kv.value_type;
            if (r.eof(4)) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: UINT32/INT32 want 4B but EOF (off=%llu left=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)r.off,(unsigned long long)(file_len-r.off)); r.ok=false; return; }
            kv.scalar_bytes.assign(r.base+r.off, r.base+r.off+4); r.off += 4; break;
        case 6:            // FLOAT32 (4B)
            kv.scalar_type = kv.value_type;
            if (r.eof(4)) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: FLOAT32 want 4B but EOF (off=%llu left=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)r.off,(unsigned long long)(file_len-r.off)); r.ok=false; return; }
            kv.scalar_bytes.assign(r.base+r.off, r.base+r.off+4); r.off += 4; break;
        case 7:            // BOOL (1B)
            kv.scalar_type = kv.value_type;
            if (r.eof(1)) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: BOOL want 1B but EOF (off=%llu left=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)r.off,(unsigned long long)(file_len-r.off)); r.ok=false; return; }
            kv.scalar_bytes.assign(r.base+r.off, r.base+r.off+1); r.off += 1; break;
        case 8:            // STRING
            kv.scalar_type = kv.value_type;
            kv.value_string = r.r_str();
            if (!r.ok) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: STRING read failed (vu64 len EOF) (off=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)r.off); return; }
            break;
        case 9: {          // ARRAY: array_type (uint32) + arr_count (uint64) + elements
            kv.arr_type  = r.r<uint32_t>();
            kv.arr_count = r.r<uint64_t>();
            if (!r.ok) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: ARRAY header read failed (off=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)r.off); return; }
            if (kv.arr_type == 8) {  // string array
                for (uint64_t i = 0; i < kv.arr_count; ++i) {
                    std::string s = r.r_str();
                    if (!r.ok) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: ARRAY(STRING) item[%llu] read failed (off=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)i,(unsigned long long)r.off); return; }
                    kv.arr_strings.push_back(std::move(s));
                }
            } else if (kv.arr_type == 13) {  // ARRAY(HAINT): 每个元素 ULEB128
                // 每个元素写 8 字节 little-endian uint64 到 arr_bytes
                kv.arr_bytes.reserve((size_t)kv.arr_count * 8);
                for (uint64_t i = 0; i < kv.arr_count; ++i) {
                    uint64_t v = r.vu64();
                    if (!r.ok) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: ARRAY(HAINT) item[%llu] vu64 failed (off=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)i,(unsigned long long)r.off); return; }
                    uint8_t buf[8];
                    memcpy(buf, &v, 8);
                    kv.arr_bytes.insert(kv.arr_bytes.end(), buf, buf+8);
                }
            } else {
                size_t es = gguf_type_size(kv.arr_type);
                if (es == 0) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: ARRAY arr_type=%u has gguf_type_size=0 (unsupported element type)",(unsigned long long)ctx_idx,ctx_key,(unsigned)kv.arr_type); r.ok=false; return; }
                size_t total = es * (size_t)kv.arr_count;
                if (r.eof(total)) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: ARRAY want %lluB (es=%u count=%llu) but EOF (off=%llu left=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)total,(unsigned)es,(unsigned long long)kv.arr_count,(unsigned long long)r.off,(unsigned long long)(file_len-r.off)); r.ok=false; return; }
                kv.arr_bytes.assign(r.base + r.off, r.base + r.off + total);
                r.off += total;
            }
            break;
        }
        case 10: case 11:  // UINT64 / INT64 (8B)
            kv.scalar_type = kv.value_type;
            if (r.eof(8)) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: UINT64/INT64 want 8B but EOF (off=%llu left=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)r.off,(unsigned long long)(file_len-r.off)); r.ok=false; return; }
            kv.scalar_bytes.assign(r.base+r.off, r.base+r.off+8); r.off += 8; break;
        case 12:           // FLOAT64 (8B)
            kv.scalar_type = kv.value_type;
            if (r.eof(8)) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: FLOAT64 want 8B but EOF (off=%llu left=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)r.off,(unsigned long long)(file_len-r.off)); r.ok=false; return; }
            kv.scalar_bytes.assign(r.base+r.off, r.base+r.off+8); r.off += 8; break;
        case 13: {         // HAINT: 新版 GGUF meta 标量 (ULEB128, 同 vu64)
            kv.scalar_type = kv.value_type;
            uint64_t v = r.vu64();
            if (!r.ok) { snprintf(s_kv_errbuf,sizeof(s_kv_errbuf),"kv[%llu] %s: HAINT vu64 read failed (off=%llu)",(unsigned long long)ctx_idx,ctx_key,(unsigned long long)r.off); return; }
            kv.scalar_bytes.resize(8);
            memcpy(kv.scalar_bytes.data(), &v, 8);
            break;
        }
        default: {
            unsigned long long left = (unsigned long long)(file_len > r.off ? file_len - r.off : 0);
            LOG("kv[%llu] %s: UNKNOWN value_type=%u (file_off=%llu left=%llu bytes, before_read_off=%llu) → CANNOT PARSE, ABORT",
                (unsigned long long)ctx_idx, ctx_key, (unsigned)kv.value_type,
                (unsigned long long)r.off, left, (unsigned long long)off_before);
            snprintf(s_kv_errbuf, sizeof(s_kv_errbuf),
                "kv[%llu] key='%s': unsupported GGUF value_type=%u (file_off=%llu left=%lluB, before_read=%lluB)",
                (unsigned long long)ctx_idx, ctx_key, (unsigned)kv.value_type,
                (unsigned long long)r.off, left, (unsigned long long)off_before);
            r.ok = false;
            return;
        }
    }
}

static int64_t kv_get_i64(const kv_entry & e) {
    if (e.scalar_bytes.size() == 8) { int64_t v; memcpy(&v, e.scalar_bytes.data(), 8); return v; }
    if (e.scalar_bytes.size() == 4) { int32_t v; memcpy(&v, e.scalar_bytes.data(), 4); return v; }
    if (e.scalar_bytes.size() == 2) { int16_t v; memcpy(&v, e.scalar_bytes.data(), 2); return v; }
    if (e.scalar_bytes.size() == 1) { return (int8_t)e.scalar_bytes[0]; }
    return 0;
}
static uint64_t kv_get_u64(const kv_entry & e) { return (uint64_t)kv_get_i64(e); }
static float kv_get_f32(const kv_entry & e) {
    if (e.scalar_bytes.size() == 4) { float v; memcpy(&v, e.scalar_bytes.data(), 4); return v; }
    if (e.scalar_bytes.size() == 8) { double v; memcpy(&v, e.scalar_bytes.data(), 8); return (float)v; }
    return 0.0f;
}

// =====================================================
//  公共 API: qwen_load_model
// =====================================================
static char * err(const char * s) {
    size_t n = strlen(s);
    char * p = (char *)malloc(n+1);
    memcpy(p, s, n+1);
    return p;
}

// GGUF tensor name → 内部规范化名: "blk.N.xxx" <-> "layers.N.xxx"
//   GGUF 标准: "blk.0.attn_q.weight"
//   为了实现简单, 直接用 GGUF 名.
char * qwen_load_model(const char * gguf_path, QwenModel * & out_model) {
    out_model = nullptr;
    if (!gguf_path) return err("gguf_path == null");
    int fd = ::open(gguf_path, O_RDONLY);
    if (fd < 0) return err("open(gguf) failed");
    struct stat st; if (::fstat(fd, &st) < 0) { ::close(fd); return err("fstat failed"); }
    size_t flen = (size_t)st.st_size;
    void * p = ::mmap(nullptr, flen, PROT_READ, MAP_PRIVATE, fd, 0);
    if (p == MAP_FAILED) { ::close(fd); return err("mmap failed"); }

    std::unique_ptr<QwenModel> m(new QwenModel());
    m->mmap_ptr = p; m->mmap_len = flen; m->mmap_fd = fd;

    gguf_reader r;
    r.base = (uint8_t *)p;
    r.len  = flen;
    s_kv_errbuf[0] = 0;
    // magic
    uint32_t magic = r.r<uint32_t>();
    if (magic != 0x46554747u /* GGUF */) {
        return err("bad magic, not GGUF");
    }
    uint32_t ver = r.r<uint32_t>();
    if (ver != 3 && ver != 2) { char b[128]; snprintf(b,sizeof(b),"only GGUF v2/v3 supported (got=%u)",ver); return err(b); }
    // 🆕 v1.3.25-fix10 关键修复：GGUF v3 规范 (官方 gguf_reader.py) 确认
    //   n_tensors_count 和 metadata_kv_count 都是 **固定 uint64 LE (8 字节)**，
    //   不是 ULEB128 (vu64). 之前 fix9 错误改成 vu64, 结果:
    //     固定 uint64 编码的 144 (0x90 00...) 被 vu64 读成 16
    //     → n_kv=16, n_tensors=16, 恰好过 sanity check (<=2000)
    //     → 但 r.off 错位 14 字节, KV 解析错, 到 tensor info 阶段报 corrupt
    uint64_t n_tensors = r.r<uint64_t>();
    uint64_t n_kv      = r.r<uint64_t>();
    if (!r.ok) {
        char b[192];
        snprintf(b,sizeof(b),"header parse failed: ver=%u (file_len=%llu off=%llu)",
                 ver,(unsigned long long)flen,(unsigned long long)r.off);
        return err(b);
    }
    LOG("GGUF HEADER: ver=%u n_tensors=%llu n_kv=%llu file_len=%lluMB next_off=%llu",
        ver, (unsigned long long)n_tensors, (unsigned long long)n_kv,
        (unsigned long long)(flen>>20), (unsigned long long)r.off);
    if (n_kv > 10000) {
        char b[160];
        snprintf(b,sizeof(b),"n_kv=%llu too large (file_len=%lluMB), likely header offset corrupt",
                 (unsigned long long)n_kv, (unsigned long long)(flen>>20));
        return err(b);
    }
    if (n_tensors > 2000) {
        char b[160];
        snprintf(b,sizeof(b),"n_tensors=%llu too large (file_len=%lluMB), header corrupt",
                 (unsigned long long)n_tensors, (unsigned long long)(flen>>20));
        return err(b);
    }

    // kv
    std::unordered_map<std::string, kv_entry> kvs;
    for (uint64_t i=0; i<n_kv; ++i) {
        size_t key_off_beg = r.off;
        std::string k = r.r_str();
        if (!r.ok) {
            char b[192];
            snprintf(b,sizeof(b),"kv[%llu/%llu]: r_str(key) failed @ off=%llu left=%lluB (str_off_before=%llu)",
                     (unsigned long long)i,(unsigned long long)n_kv,
                     (unsigned long long)r.off,(unsigned long long)(flen-r.off),
                     (unsigned long long)key_off_beg);
            return err(b);
        }
        size_t val_off_beg = r.off;
        kv_entry e;
        consume_kv_value(r, e, i, k.c_str(), flen);
        if (!r.ok) {
            const char * why = s_kv_errbuf[0] ? s_kv_errbuf : "no-detail";
            LOG("KV FAIL kv[%llu/%llu] '%s' @ value_begin_off=%llu key_begin_off=%llu left=%lluB — detail=%s",
                (unsigned long long)i, (unsigned long long)n_kv, k.c_str(),
                (unsigned long long)val_off_beg, (unsigned long long)key_off_beg,
                (unsigned long long)(flen-val_off_beg), why);
            // 返回可定位的错误（不是"kv parse failed"）
            char b[512];
            snprintf(b, sizeof(b),
                "kv[%llu/%llu] key='%s' parse failed (ver=%u). Details: %s | "
                "file_len=%lluMB key_off=%llu value_off=%llu left=%lluB | "
                "请发下一条日志的 full qwen-loader 给我.",
                (unsigned long long)i, (unsigned long long)n_kv, k.c_str(), ver, why,
                (unsigned long long)(flen>>20),
                (unsigned long long)key_off_beg, (unsigned long long)val_off_beg,
                (unsigned long long)(flen > val_off_beg ? flen-val_off_beg : 0));
            return err(b);
        }
        // 打一条简短成功日志（避免 150K merges 刷屏, 只对非 tokenizer merge/score 的 KV 打详情长度）
        unsigned is_merge = (k.find("merges") != std::string::npos) ? 1 : 0;
        if (is_merge) {
            if ((i & 0xFFF) == 0) {  // 每 4096 条 merge 打一条心跳
                LOG("kv[%llu/%llu] '%s' type=%u arr_strings_count=%zu (heartbeat)",
                    (unsigned long long)i,(unsigned long long)n_kv,k.c_str(),(unsigned)e.value_type,
                    (size_t)(e.arr_type==8?e.arr_strings.size():e.arr_count));
            }
        } else {
            char val_desc[96]; val_desc[0] = 0;
            if (e.value_type == 8) snprintf(val_desc,sizeof(val_desc),"STRING len=%zu", e.value_string.size());
            else if (e.value_type == 9) {
                if (e.arr_type == 8) snprintf(val_desc,sizeof(val_desc),"ARRAY(STRING) count=%zu", e.arr_strings.size());
                else snprintf(val_desc,sizeof(val_desc),"ARRAY(type=%u count=%llu bytes=%zu)", (unsigned)e.arr_type,(unsigned long long)e.arr_count,e.arr_bytes.size());
            }
            else snprintf(val_desc,sizeof(val_desc),"SCALAR(type=%u bytes=%zu)", (unsigned)e.scalar_type, e.scalar_bytes.size());
            LOG("kv[%llu/%llu] '%s' = vtype=%u %s (off_after=%llu / total %lluMB)",
                (unsigned long long)i,(unsigned long long)n_kv,k.c_str(),(unsigned)e.value_type,
                val_desc,(unsigned long long)r.off,(unsigned long long)(flen>>20));
        }
        kvs.emplace(std::move(k), std::move(e));
    }
    LOG("KV DONE: parsed %llu/%llu KVs, next_off=%llu, left=%llu bytes",
        (unsigned long long)n_kv, (unsigned long long)n_kv,
        (unsigned long long)r.off, (unsigned long long)(flen > r.off ? flen-r.off : 0));

    // --- 填配置 ---
    auto & cfg = m->cfg;
    auto getk = [&](const char * k) -> kv_entry * {
        auto it = kvs.find(k); return it == kvs.end() ? nullptr : &it->second;
    };
    auto get_arr_i = [&](const char * k, size_t idx) -> int64_t {
        auto * e = getk(k); if (!e || e->arr_count <= idx) return 0;
        size_t es = gguf_type_size(e->arr_type);
        if (e->arr_bytes.size() < es*(idx+1)) return 0;
        if (es == 8) { int64_t v; memcpy(&v, e->arr_bytes.data() + es*idx, 8); return v; }
        if (es == 4) { int32_t v; memcpy(&v, e->arr_bytes.data() + es*idx, 4); return v; }
        return 0;
    };
    auto get_arr_s = [&](const char * k, size_t idx) -> std::string {
        auto * e = getk(k); if (!e || e->arr_count <= idx) return {};
        if (e->arr_type == 8 && idx < e->arr_strings.size()) return e->arr_strings[idx];
        return {};
    };
    if (auto * e = getk("general.architecture")) cfg = QwenModelConfig();   // 目前只支持 qwen2 (也兼容 qwen2.5)
    if (auto * e = getk("qwen2.block_count"))            cfg.n_layer      = (int32_t)kv_get_i64(*e);
    if (auto * e = getk("qwen2.embedding_length"))       cfg.n_embd       = (int32_t)kv_get_i64(*e);
    if (auto * e = getk("qwen2.feed_forward_length"))    cfg.n_ff         = (int32_t)kv_get_i64(*e);
    if (auto * e = getk("qwen2.attention.head_count"))   cfg.n_head       = (int32_t)kv_get_i64(*e);
    if (auto * e = getk("qwen2.attention.head_count_kv"))cfg.n_head_kv    = (int32_t)kv_get_i64(*e);
    if (auto * e = getk("qwen2.attention.rope.freq_base"))cfg.rope_freq_base = kv_get_f32(*e);
    if (auto * e = getk("qwen2.attention.layer_norm_rms_epsilon")) cfg.rms_norm_eps = kv_get_f32(*e);
    if (auto * e = getk("qwen2.context_length"))         cfg.max_seq_len  = (int32_t)kv_get_i64(*e);
    if (auto * e = getk("qwen2.tensor.data_layout"))      (void)kv_get_i64(*e);
    if (cfg.n_head) cfg.head_dim = cfg.n_embd / cfg.n_head;
    cfg.rope_dim = cfg.head_dim;
    if (auto * e = getk("tokenizer.ggml.tokens")) {
        for (uint64_t i=0; i<e->arr_count; ++i) m->tok.id_to_token.push_back(get_arr_s("tokenizer.ggml.tokens", i));
    }
    if (auto * e = getk("tokenizer.ggml.scores")) {
        m->tok.id_to_score.reserve(e->arr_count);
        for (uint64_t i=0; i<e->arr_count; ++i) {
            size_t es = gguf_type_size(e->arr_type);
            float f = 0;
            if (es == 4) { float v; memcpy(&v, e->arr_bytes.data() + 4*i, 4); f = v; }
            else if (es == 8) { double v; memcpy(&v, e->arr_bytes.data() + 8*i, 8); f = (float)v; }
            m->tok.id_to_score.push_back(f);
        }
    } else {
        m->tok.id_to_score.assign(m->tok.id_to_token.size(), 0.0f);
    }
    if (auto * e = getk("tokenizer.ggml.token_type")) {
        m->tok.id_to_type.reserve(e->arr_count);
        for (uint64_t i=0; i<e->arr_count; ++i) {
            size_t es = gguf_type_size(e->arr_type);
            uint32_t v = 0;
            if (es == 4) { uint32_t x; memcpy(&x, e->arr_bytes.data()+4*i,4); v=x; }
            else if (es == 1) v = e->arr_bytes[i];
            m->tok.id_to_type.push_back(v);
        }
    } else {
        m->tok.id_to_type.assign(m->tok.id_to_token.size(), 0);
    }
    if (!m->tok.id_to_token.empty()) cfg.vocab_size = (int32_t)m->tok.id_to_token.size();
    m->tok.vocab_size_stored = cfg.vocab_size;
    m->tok.build_encode_map();

    if (auto * e = getk("tokenizer.ggml.bos_token_id"))    cfg.bos_id    = (int32_t)kv_get_i64(*e);
    if (auto * e = getk("tokenizer.ggml.eos_token_id"))    cfg.eos_id    = (int32_t)kv_get_i64(*e);
    // im_end 不在 GGUF meta, 用硬编码 151645
    cfg.im_end_id = 151645;
    if (cfg.max_seq_len > 32768) cfg.max_seq_len = 4096;  // 手机上 32K 内存太挤, 先限 4K
    if (cfg.max_seq_len <= 0)    cfg.max_seq_len = 4096;

    // --- 读 tensor 信息 ---
    // GGUF v3 tensor info format (fixed-size integers, NOT LEB128):
    //   name: string (ULEB128 len + bytes)
    //   n_dims: uint32 (4B)
    //   dims[n_dims]: uint64 each (8B each)
    //   dtype: uint32 (4B)
    //   offset: uint64 (8B)
    // Bug fix v1.3.25: ne[d] and off were read with vu64() (LEB128),
    //   but GGUF uses fixed uint64. This caused "tensor header corrupt"
    //   because the read position was wrong.
    // Bug fix v1.3.25-fix4: tensor offset 是相对于 weights section 起点的.
    // weights section 起点 = 所有 header (kv info + tensor info) 读完后对齐到 alignment.
    // 之前在循环内用 r.off 算, r.off 是当前 tensor info 读完后的位置, 完全错.
    // 现在: 先收集所有 tensor info, 读完所有 tensor info 后算 weights_start, 再算 abs_off.

    struct raw_tensor_info {
        std::string name;
        uint32_t    ndim;
        size_t      ne[4];
        uint32_t    dtype;
        uint64_t    off;
    };
    std::vector<raw_tensor_info> raw_tensors;
    raw_tensors.reserve(n_tensors);

    for (uint64_t i=0; i<n_tensors; ++i) {
        raw_tensor_info ti;
        ti.name = r.r_str();
        ti.ndim = r.r<uint32_t>();
        if (ti.ndim > 4 || !r.ok) return err("tensor header corrupt");
        ti.ne[0] = ti.ne[1] = ti.ne[2] = ti.ne[3] = 1;
        for (uint32_t d=0; d<ti.ndim; ++d) ti.ne[d] = (size_t)r.r<uint64_t>();
        ti.dtype = r.r<uint32_t>();
        ti.off   = r.r<uint64_t>();
        if (!r.ok) return err("tensor info parse failed");
        raw_tensors.push_back(std::move(ti));
    }

    // 所有 header 读完, 算 weights section 起点
    uint32_t alignment = 32;
    if (auto * e = getk("general.alignment")) alignment = (uint32_t)kv_get_i64(*e);
    if (alignment < 1) alignment = 32;
    uint64_t header_end   = r.off;
    uint64_t weights_start = (header_end + alignment - 1) / alignment * alignment;
    LOG("GGUF header_end=%llu weights_start=%llu alignment=%u", (unsigned long long)header_end, (unsigned long long)weights_start, alignment);

    for (auto & ti : raw_tensors) {
        auto * t = new QwenTensor();
        t->name = std::move(ti.name);
        t->type = (int)ti.dtype;
        t->ndim = ti.ndim;
        memcpy(t->ne, ti.ne, sizeof(ti.ne));

        size_t es = ggml_type_size((int)ti.dtype);
        size_t bs = ggml_blck_size((int)ti.dtype);
        size_t ne0_blocks = (ti.ne[0] + bs - 1)/bs;
        t->nb[0] = es;
        t->nb[1] = ne0_blocks * es;
        t->nb[2] = t->nb[1] * ti.ne[1];
        t->nb[3] = t->nb[2] * ti.ne[2];

        // tensor offset 相对于 weights_start (GGUF v3 spec)
        uint64_t abs_off = weights_start + ti.off;
        if (abs_off >= flen) { delete t; return err("tensor offset out of range"); }
        size_t total_bytes = t->nb[ti.ndim==0?1:ti.ndim-1] * (ti.ndim==0?1:ti.ne[ti.ndim-1]);
        if (abs_off + total_bytes > flen) { delete t; return err("tensor data out of range"); }

        t->data = (char*)p + abs_off;
        t->owned = false;
        t->size_bytes = total_bytes;
        m->tensors.push_back(t);
        LOG("  tensor %s dtype=%u off=%llu abs=%llu bytes=%zu ne=[%zu,%zu,%zu,%zu]",
            t->name.c_str(), ti.dtype, (unsigned long long)ti.off, (unsigned long long)abs_off, total_bytes,
            ti.ne[0], ti.ne[1], ti.ne[2], ti.ne[3]);
    }

    m->build_name_map();
    out_model = m.release();
    return nullptr;
}

void qwen_free_model(QwenModel * m) { delete m; }

// =====================================================
//  Decode / Encode (极简实现)
// =====================================================
std::string qwen_decode_token(const QwenModel * m, int32_t id) {
    if (!m) return {};
    if (id < 0 || id >= (int32_t)m->tok.id_to_token.size()) return {};
    return m->tok.id_to_token[id];
}

std::vector<int32_t> qwen_encode_text(const QwenModel * m, const std::string & text, bool add_bos) {
    std::vector<int32_t> out;
    if (!m) return out;
    if (add_bos) out.push_back(m->cfg.bos_id);
    // 简化 BPE:
    //   先按 byte 切开 -> 每个单字节找 id; 若 piece 不在词表里 -> bytefallback:
    //     Qwen 词表为每个 0x00..0xFF 单字节都有 id (token_type=2, piece 形如 <0xAB>).
    //   然后对相邻两个 (a,b) 尝试 merge, 选 最大 score (最小 rank) 的组合, 迭代到不能合并.
    //   由于 Qwen BPE 训练时就包含单字节 bytefallback, 所以一定能完整 cover text.
    auto & tok = m->tok;

    // 步骤 1: byte sequence -> 初始 symbols
    struct sym { int32_t id; std::string bytes; };
    std::vector<sym> seq;
    for (size_t i = 0; i < text.size();) {
        // 先尝试以长匹配 (最长 24B) 命中 token
        int best_id = -1;
        int best_len = -1;
        for (int L = std::min<int>(24, (int)(text.size() - i)); L >= 1; --L) {
            std::string piece = text.substr(i, L);
            int id = tok.id(piece);
            if (id >= 0) {
                // 仅当 type != special 时拿来 encode text
                if (id < (int)tok.id_to_type.size() && tok.id_to_type[id] == 1) continue;
                best_id = id;
                best_len = L;
                break;
            }
        }
        if (best_id >= 0 && best_len > 0) {
            seq.push_back({best_id, text.substr(i, best_len)});
            i += best_len;
        } else {
            // 单字节 bytefallback: 找 "<0xXX>" id
            unsigned char b = (unsigned char)text[i];
            char hex[8]; snprintf(hex, sizeof(hex), "<0x%02X>", b);
            int id = tok.id(hex);
            if (id < 0) {
                // 找 lowercase hex
                snprintf(hex, sizeof(hex), "<0x%02x>", b);
                id = tok.id(hex);
            }
            if (id < 0) {
                // 最后兜底: 直接把该字节作为 piece 找 id (Qwen2 GGUF 有些版本直接是单字节)
                std::string bs(1, (char)b);
                id = tok.id(bs);
            }
            if (id < 0) {
                // 词表缺: 塞 0
                id = 0;
            }
            seq.push_back({id, std::string(1, (char)b)});
            i += 1;
        }
    }

    // 步骤 2: merge 迭代 (标准 BPE)
    while (seq.size() > 1) {
        float   best_score = -1e30f;
        int     best_i     = -1;
        int     best_id    = -1;
        for (size_t i = 0; i + 1 < seq.size(); ++i) {
            std::string merged = seq[i].bytes + seq[i+1].bytes;
            int id = tok.id(merged);
            if (id < 0) continue;
            if (id >= (int)tok.id_to_type.size() || tok.id_to_type[id] == 1) continue;
            float sc = (id < (int)tok.id_to_score.size()) ? tok.id_to_score[id] : 0.0f;
            // 分数越高越先合并 (Qwen 写 vocab merge rank 通常越小越先, 但 GGUF scores 实际存储可能 = -rank;
            // 为了对不同写法鲁棒, 这里用 scores 直接按"大优先". 若没写入 scores(全0), 合并顺序任意.)
            if (sc > best_score || (sc == best_score && best_i < 0)) {
                best_score = sc;
                best_i = (int)i;
                best_id = id;
            }
        }
        if (best_i < 0) break;
        sym merged_sym;
        merged_sym.id    = best_id;
        merged_sym.bytes = seq[best_i].bytes + seq[best_i+1].bytes;
        seq.erase(seq.begin() + best_i, seq.begin() + best_i + 2);
        seq.insert(seq.begin() + best_i, merged_sym);
    }
    for (auto & s : seq) out.push_back(s.id);
    return out;
}

// =====================================================
std::string qwen_dump_model(const QwenModel * m) {
    std::string out;
    char buf[256];
    auto & c = m->cfg;
    snprintf(buf, sizeof(buf), "arch=qwen2 n_layer=%d n_embd=%d n_head=%d n_head_kv=%d head_dim=%d n_ff=%d vocab=%d\n",
             c.n_layer, c.n_embd, c.n_head, c.n_head_kv, c.head_dim, c.n_ff, c.vocab_size);
    out += buf;
    snprintf(buf, sizeof(buf), "bos=%d eos=%d im_end=%d rope_base=%.1f eps=%.7f tie=%d\n",
             c.bos_id, c.eos_id, c.im_end_id, c.rope_freq_base, c.rms_norm_eps, (int)c.tie_embeddings);
    out += buf;
    snprintf(buf, sizeof(buf), "tensors=%zu  first 12 tensor names:\n", m->tensors.size());
    out += buf;
    for (size_t i = 0; i < m->tensors.size() && i < 12; ++i) {
        auto * t = m->tensors[i];
        snprintf(buf, sizeof(buf), "  [%02zu] %s  type=%d dims=%zdx%zdx%zdx%zd  bytes=%zd\n",
                 i, t->name.c_str(), t->type, t->ne[0], t->ne[1], t->ne[2], t->ne[3], t->size_bytes);
        out += buf;
    }
    return out;
}
