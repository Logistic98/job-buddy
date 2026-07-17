package com.jobbuddy.backend.modules.auth.dto.request;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

@Data
public class UserRolesRequest {
  private List<String> roleIds = new ArrayList<String>();
}
