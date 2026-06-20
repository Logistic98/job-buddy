package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.vo.IntentResult;

public interface IntentService {
    IntentResult classify(String message);
}
