// qwen_forward.h — 自写 forward pass 对外接口 (v1.3.25)
// 零 ggml 依赖，纯数学算子
#pragma once

#include "qwen_infer.h"
#include <cstdint>

void fwd_set_log_cb(qwen_cb_log cb, void * ud);

bool fwd_forward_step(QwenSession * sess, QwenModel * model,
                      int32_t token_id, int32_t pos,
                      float * logits_out);

int fwd_sample(float * logits, int vocab_size,
               float temperature, float top_p, int top_k,
               uint32_t seed, int step);
