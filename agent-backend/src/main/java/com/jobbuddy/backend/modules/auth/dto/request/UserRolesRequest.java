package com.jobbuddy.backend.modules.auth.dto.request;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 承载用户角色列表请求参数。
 */
@Data
public class UserRolesRequest {
  private List<String> roleIds = new ArrayList<String>();
}
