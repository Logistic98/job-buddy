package com.jobbuddy.backend.modules.job.dto.command;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存收藏岗位的业务命令对象。
 *
 * 前端岗位卡片字段仍然是动态快照，Service 层只接收命令对象，避免 Controller 直接把裸 Map 透传到业务层。
 */
public class JobFavoriteSaveCommand {
    private final Map<String, Object> jobSnapshot;

    private JobFavoriteSaveCommand(Map<String, Object> jobSnapshot) {
        this.jobSnapshot = jobSnapshot == null
                ? Collections.<String, Object>emptyMap()
                : new LinkedHashMap<String, Object>(jobSnapshot);
    }

    public static JobFavoriteSaveCommand from(Map<String, Object> jobSnapshot) {
        return new JobFavoriteSaveCommand(jobSnapshot);
    }

    public static JobFavoriteSaveCommand empty() {
        return new JobFavoriteSaveCommand(Collections.<String, Object>emptyMap());
    }

    public boolean isEmpty() {
        return jobSnapshot.isEmpty();
    }

    public Map<String, Object> toSnapshot() {
        return new LinkedHashMap<String, Object>(jobSnapshot);
    }
}
