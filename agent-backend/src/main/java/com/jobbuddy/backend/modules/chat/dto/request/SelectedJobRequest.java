package com.jobbuddy.backend.modules.chat.dto.request;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Data;

/**
 * 已选岗位分析请求。只接收匹配所需的有界字段，忽略 Boss 详情中的原始响应、推荐元数据和其他嵌套对象。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SelectedJobRequest {
  @Size(max = 256, message = "岗位标识长度不能超过 256")
  @JsonAlias({"security_id", "id", "jobId", "encryptJobId", "encrypt_job_id"})
  private String securityId;

  @Size(max = 512, message = "岗位名称长度不能超过 512")
  @JsonAlias({"job_name", "title", "name"})
  private String jobName;

  @Size(max = 512, message = "公司名称长度不能超过 512")
  @JsonAlias({"brandName", "companyName"})
  private String company;

  @Size(max = 256, message = "薪资描述长度不能超过 256")
  @JsonAlias({"salaryDesc", "salaryText", "jobSalary"})
  private String salary;

  @Size(max = 256, message = "城市描述长度不能超过 256")
  @JsonAlias({"cityName", "location", "areaDistrict"})
  private String city;

  @Size(max = 256, message = "经验要求长度不能超过 256")
  @JsonAlias({"jobExperience", "experienceName"})
  private String experience;

  @Size(max = 256, message = "学历要求长度不能超过 256")
  @JsonAlias({"jobDegree", "education", "degreeName"})
  private String degree;

  @Size(max = 256, message = "行业描述长度不能超过 256")
  @JsonAlias({"brandIndustry", "companyIndustry", "industryName"})
  private String industry;

  @Size(max = 2048, message = "岗位链接长度不能超过 2048")
  @JsonAlias({"jobUrl", "url", "href", "link", "detailUrl", "jobDetailUrl"})
  private String originalUrl;

  @Size(max = 2400, message = "岗位描述长度不能超过 2400")
  @JsonAlias({
    "description",
    "postDescription",
    "jobDesc",
    "jobSecText",
    "detailText",
    "jobRequire",
    "jobContent"
  })
  private String jobDescription;

  @Valid
  @Size(max = 12, message = "岗位技能标签不能超过 12 个")
  private List<@Size(max = 120, message = "单个岗位技能标签长度不能超过 120") String> skills;

  @Valid
  @Size(max = 12, message = "岗位标签不能超过 12 个")
  @JsonAlias({"labels", "welfareList"})
  private List<@Size(max = 120, message = "单个岗位标签长度不能超过 120") String> jobLabels;

  /**
   * 转换为现有岗位解析器使用的紧凑映射。
   *
   * @return 只包含非空白字段的岗位映射
   */
  public Map<String, Object> toMap() {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    putText(result, "securityId", securityId);
    putText(result, "jobName", jobName);
    putText(result, "company", company);
    putText(result, "salary", salary);
    putText(result, "city", city);
    putText(result, "experience", experience);
    putText(result, "degree", degree);
    putText(result, "industry", industry);
    putText(result, "originalUrl", originalUrl);
    putText(result, "jobDescription", jobDescription);
    putList(result, "skills", skills);
    putList(result, "jobLabels", jobLabels);
    return result;
  }

  private static void putText(Map<String, Object> target, String key, String value) {
    if (value != null && !value.trim().isEmpty()) target.put(key, value.trim());
  }

  private static void putList(Map<String, Object> target, String key, List<String> values) {
    if (values == null || values.isEmpty()) return;
    List<String> normalized =
        values.stream()
            .filter(java.util.Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();
    if (!normalized.isEmpty()) target.put(key, normalized);
  }
}
