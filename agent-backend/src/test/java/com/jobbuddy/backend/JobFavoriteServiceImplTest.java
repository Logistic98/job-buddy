package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisPartialResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossFavoriteListResult;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteAnalysisCommand;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import com.jobbuddy.backend.modules.job.dto.request.BossFavoriteImportRequest;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoriteImportResponse;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoritePreviewResponse;
import com.jobbuddy.backend.modules.job.mapper.JobFavoriteMapper;
import com.jobbuddy.backend.modules.job.service.impl.JobFavoriteServiceImpl;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JobFavoriteServiceImplTest {

  @Test
  void saveFavoriteShouldKeepExistingDescriptionWithoutFetchingDetail() {
    Fixture fixture = new Fixture();
    Map<String, Object> job = job("sec-1");
    job.put("jobDescription", "负责 Java 与 Agent 平台研发");

    fixture.service.saveFavorite(
        "user-1", JobFavoriteSaveCommand.from(fixture.jsonCodec.toTree(job)));

    verify(fixture.bossCliService, never()).jobDetail(anyString(), anyString());
    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(fixture.mapper).upsertFavorite(anyString(), anyString(), anyString(), json.capture());
    assertEquals(
        "负责 Java 与 Agent 平台研发", fixture.jsonCodec.toMap(json.getValue()).get("jobDescription"));
  }

  @Test
  void saveFavoriteShouldFetchAndPersistDescriptionAtFavoriteTime() {
    Fixture fixture = new Fixture();
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("description", "负责大模型应用研发与系统交付");
    when(fixture.bossCliService.jobDetail("sec-1", "https://www.zhipin.com/job_detail/sec-1.html"))
        .thenReturn(fixture.jsonCodec.toTree(detail));
    Map<String, Object> job = job("sec-1");
    job.put("originalUrl", "https://www.zhipin.com/job_detail/sec-1.html");

    fixture.service.saveFavorite(
        "user-1", JobFavoriteSaveCommand.from(fixture.jsonCodec.toTree(job)));

    ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
    verify(fixture.mapper).upsertFavorite(anyString(), anyString(), anyString(), json.capture());
    assertEquals("负责大模型应用研发与系统交付", fixture.jsonCodec.toMap(json.getValue()).get("jobDescription"));
  }

  @Test
  void saveFavoriteShouldNotPersistWhenDescriptionCollectionFails() {
    Fixture fixture = new Fixture();
    when(fixture.bossCliService.jobDetail("sec-1", ""))
        .thenThrow(new RuntimeException("Boss 岗位详情不可用"));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                fixture.service.saveFavorite(
                    "user-1", JobFavoriteSaveCommand.from(fixture.jsonCodec.toTree(job("sec-1")))));

    assertTrue(error.getMessage().contains("不可用"));
    verify(fixture.mapper, never())
        .upsertFavorite(anyString(), anyString(), anyString(), anyString());
  }

  @Test
  void analyzeFavoriteShouldFetchAndPersistDescriptionOnlyAfterExplicitRequest() {
    Fixture fixture = new Fixture();
    when(fixture.mapper.findFavorite("user-1", "sec-1"))
        .thenReturn(row(fixture.jsonCodec, job("sec-1")));
    when(fixture.bossCliService.jobDetail("sec-1", ""))
        .thenReturn(
            fixture.jsonCodec.toTree(Collections.singletonMap("description", "负责 Java 大模型应用研发")));
    when(fixture.resumeStorageService.get("resume-1", "user-1")).thenReturn(resume());
    when(fixture.jobRuntimeService.matchResume(any(), any(), any()))
        .thenReturn(matchOutput(mapOf("score", 88, "recommendation", "推荐")));

    Map<String, Object> result =
        fixture.jsonCodec.toMap(
            fixture
                .service
                .analyzeFavorite("user-1", JobFavoriteAnalysisCommand.of("sec-1", "resume-1"))
                .value());

    assertEquals(88, ((Map<?, ?>) ((Map<?, ?>) result.get("analysis")).get("match")).get("score"));
    verify(fixture.bossCliService).jobDetail("sec-1", "");
    ArgumentCaptor<String> jobJson = ArgumentCaptor.forClass(String.class);
    verify(fixture.mapper).upsertFavorite(anyString(), anyString(), anyString(), jobJson.capture());
    assertEquals(
        "负责 Java 大模型应用研发", fixture.jsonCodec.toMap(jobJson.getValue()).get("jobDescription"));
    verify(fixture.mapper)
        .updateAnalysis(anyString(), anyString(), anyString(), any(Instant.class));
  }

  @Test
  void analyzeJobIncrementallyShouldPublishThreeRealPartialReports() {
    Fixture fixture = new Fixture();
    Map<String, Object> job = job("sec-1");
    job.put("jobDescription", "负责 Java Agent 平台研发");
    when(fixture.mapper.findFavorite("user-1", "sec-1")).thenReturn(row(fixture.jsonCodec, job));
    when(fixture.resumeStorageService.get("resume-1", "user-1")).thenReturn(resume());
    when(fixture.jobRuntimeService.matchResumeSections(
            any(), any(), org.mockito.ArgumentMatchers.isNull(), any()))
        .thenReturn(matchOutput(mapOf("score", 82, "reasoning", "值得投递")))
        .thenReturn(
            matchOutput(
                mapOf(
                    "dimensions", Collections.singletonMap("technical_skill", mapOf("score", 85)))))
        .thenReturn(matchOutput(mapOf("interview_focus", Arrays.asList("准备 Agent 架构"))));
    List<AnalysisPartialResult> partials = new ArrayList<AnalysisPartialResult>();

    Map<String, Object> result =
        fixture.jsonCodec.toMap(
            fixture
                .service
                .analyzeJobIncrementally(
                    "user-1",
                    JobFavoriteSaveCommand.from(fixture.jsonCodec.toTree(job)),
                    "resume-1",
                    partials::add)
                .value());

    assertEquals(3, partials.size());
    Map<String, Object> firstPayload = fixture.jsonCodec.toMap(partials.get(0).getPayload());
    Map<?, ?> firstMatch = (Map<?, ?>) ((Map<?, ?>) firstPayload.get("analysis")).get("match");
    assertEquals(82, firstMatch.get("score"));
    assertTrue(!firstMatch.containsKey("dimensions"));
    Map<?, ?> finalMatch = (Map<?, ?>) ((Map<?, ?>) result.get("analysis")).get("match");
    assertEquals(
        85,
        ((Map<?, ?>) ((Map<?, ?>) finalMatch.get("dimensions")).get("technical_skill"))
            .get("score"));
    assertEquals("准备 Agent 架构", ((List<?>) finalMatch.get("interview_focus")).get(0));
    verify(fixture.mapper)
        .updateAnalysis(anyString(), anyString(), anyString(), any(Instant.class));
  }

  @Test
  void analyzeFavoriteShouldNotPersistEmptyRuntimeResult() {
    Fixture fixture = new Fixture();
    Map<String, Object> job = job("sec-1");
    job.put("jobDescription", "负责 Java 服务端开发");
    when(fixture.mapper.findFavorite("user-1", "sec-1")).thenReturn(row(fixture.jsonCodec, job));
    when(fixture.resumeStorageService.get("resume-1", "user-1")).thenReturn(resume());
    when(fixture.jobRuntimeService.matchResume(any(), any(), any()))
        .thenReturn(Collections.<String, Object>singletonMap("matches", Collections.emptyList()));

    RuntimeException error =
        assertThrows(
            RuntimeException.class,
            () ->
                fixture.service.analyzeFavorite(
                    "user-1", JobFavoriteAnalysisCommand.of("sec-1", "resume-1")));

    assertTrue(error.getMessage().contains("未生成有效结果"));
    verify(fixture.mapper, never())
        .updateAnalysis(anyString(), anyString(), anyString(), any(Instant.class));
  }

  @Test
  void analyzeFavoriteShouldPersistOnlyAnalysisPayloadAndTimestamp() {
    Fixture fixture = new Fixture();
    Map<String, Object> job = job("sec-1");
    job.put("jobDescription", "负责 Java 服务端开发");
    when(fixture.mapper.findFavorite("user-1", "sec-1")).thenReturn(row(fixture.jsonCodec, job));
    when(fixture.resumeStorageService.get("resume-1", "user-1")).thenReturn(resume());
    Map<String, Object> match = new LinkedHashMap<String, Object>();
    match.put("score", 86);
    match.put("recommendation", "建议投递");
    Map<String, Object> runtimeResult = new LinkedHashMap<String, Object>();
    runtimeResult.put("schema", "resume_match.v1");
    runtimeResult.put("matches", Arrays.asList(match));
    when(fixture.jobRuntimeService.matchResume(any(), any(), any())).thenReturn(runtimeResult);

    Map<String, Object> result =
        fixture.jsonCodec.toMap(
            fixture
                .service
                .analyzeFavorite("user-1", JobFavoriteAnalysisCommand.of("sec-1", "resume-1"))
                .value());

    assertEquals(86, ((Map<?, ?>) ((Map<?, ?>) result.get("analysis")).get("match")).get("score"));
    ArgumentCaptor<String> analysisJson = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Instant> analyzedAt = ArgumentCaptor.forClass(Instant.class);
    verify(fixture.mapper)
        .updateAnalysis(anyString(), anyString(), analysisJson.capture(), analyzedAt.capture());
    Map<String, Object> persisted = fixture.jsonCodec.toMap(analysisJson.getValue());
    assertEquals("resume-1", persisted.get("resumeId"));
    assertEquals(86, ((Map<?, ?>) persisted.get("match")).get("score"));
    assertTrue(!persisted.containsKey("jobName"));
    assertEquals(Instant.parse(String.valueOf(persisted.get("analyzedAt"))), analyzedAt.getValue());
  }

  @Test
  void previewBossFavoritesShouldHideExistingAndPageDuplicatesWithoutFetchingDetail() {
    Fixture fixture = new Fixture();
    Map<String, Object> imported = job("expired-sec-1");
    imported.put("encryptJobId", "stable-job-1");
    Map<String, Object> refreshedExisting = job("rotated-sec-1");
    refreshedExisting.put("encryptJobId", "stable-job-1");
    Map<String, Object> candidate = job("sec-2-a");
    candidate.put("encryptJobId", "stable-job-2");
    Map<String, Object> duplicate = job("sec-2-b");
    duplicate.put("encryptJobId", "stable-job-2");
    when(fixture.bossCliService.favoriteJobs(1, false))
        .thenReturn(
            new BossFavoriteListResult(
                Arrays.asList(
                    fixture.jsonCodec.toTree(refreshedExisting),
                    fixture.jsonCodec.toTree(candidate),
                    fixture.jsonCodec.toTree(duplicate)),
                1,
                true,
                8,
                3,
                fixture.jsonCodec.toTree(
                    Collections.singletonMap("favorite_list_limit_hour", 12))));
    when(fixture.mapper.listFavorites("user-1"))
        .thenReturn(Collections.singletonList(row(fixture.jsonCodec, imported)));

    BossFavoritePreviewResponse result = fixture.service.previewBossFavorites("user-1", 1, false);

    assertEquals(1, result.getJobs().size());
    assertEquals("stable-job-2", result.getJobs().get(0).get("favoriteKey").asText());
    assertTrue(result.isHasMore());
    assertEquals(8, result.getTotalCount());
    assertEquals(3, result.getTotalPages());
    verify(fixture.mapper, times(1)).listFavorites("user-1");
    verify(fixture.mapper, never()).findFavorite(anyString(), anyString());
    verify(fixture.bossCliService, never()).jobDetail(anyString(), anyString());
  }

  @Test
  void importBossFavoritesShouldAcceptMoreThanFiveSelectedJobs() {
    Fixture fixture = new Fixture();
    List<JsonNode> snapshots = new ArrayList<JsonNode>();
    for (int index = 1; index <= 6; index++) {
      Map<String, Object> selected = job("sec-" + index);
      selected.put("jobDescription", "负责 Java 大模型应用研发 " + index);
      snapshots.add(fixture.jsonCodec.toTree(selected));
    }
    BossFavoriteImportRequest request = new BossFavoriteImportRequest();
    request.setJobs(snapshots);

    BossFavoriteImportResponse result = fixture.service.importBossFavorites("user-1", request);

    assertEquals(6, result.getImportedCount());
    assertEquals(0, result.getFailedCount());
    verify(fixture.mapper, times(6))
        .upsertFavorite(anyString(), anyString(), anyString(), anyString());
    verify(fixture.mapper, never()).findFavorite(anyString(), anyString());
    verify(fixture.bossCliService, never()).jobDetail(anyString(), anyString());
  }

  @Test
  void importBossFavoritesShouldPersistSummariesWithoutFetchingDetails() {
    Fixture fixture = new Fixture();
    when(fixture.bossCliService.jobDetail(anyString(), anyString()))
        .thenThrow(new AssertionError("导入摘要时不应请求 Boss 岗位详情"));
    BossFavoriteImportRequest request = new BossFavoriteImportRequest();
    request.setJobs(
        Arrays.asList(
            fixture.jsonCodec.toTree(job("sec-1")),
            fixture.jsonCodec.toTree(job("sec-2")),
            fixture.jsonCodec.toTree(job("sec-3"))));

    BossFavoriteImportResponse result = fixture.service.importBossFavorites("user-1", request);

    assertEquals(3, result.getImportedCount());
    assertEquals(0, result.getFailedCount());
    assertEquals(0, result.getUnprocessedCount());
    assertEquals(false, result.isStopped());
    verify(fixture.mapper, times(3))
        .upsertFavorite(anyString(), anyString(), anyString(), anyString());
    verify(fixture.bossCliService, never()).jobDetail(anyString(), anyString());
    verify(fixture.jobRuntimeService, never()).matchResume(any(), any(), any());
    verify(fixture.jobRuntimeService, never()).matchResumeSections(any(), any(), any(), any());
  }

  private static Map<String, Object> matchOutput(Map<String, Object> match) {
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("matches", Collections.singletonList(match));
    output.put("evaluation_schema", "resume_match.v2");
    return output;
  }

  private static Map<String, Object> mapOf(Object... values) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    for (int i = 0; i + 1 < values.length; i += 2)
      result.put(String.valueOf(values[i]), values[i + 1]);
    return result;
  }

  private static Map<String, Object> job(String securityId) {
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", securityId);
    job.put("jobName", "Java 工程师");
    job.put("brandName", "示例公司");
    return job;
  }

  private static Map<String, Object> row(JsonCodec jsonCodec, Map<String, Object> job) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("jobKey", job.get("securityId"));
    row.put("jobJson", jsonCodec.toJson(job));
    return row;
  }

  private static ResumeRecord resume() {
    ResumeRecord resume = new ResumeRecord();
    resume.setResumeId("resume-1");
    resume.setOriginalName("resume.pdf");
    resume.setParsed(
        Collections.<String, Object>singletonMap("skills", Arrays.asList("Java", "Spring")));
    return resume;
  }

  private static final class Fixture {
    private final JobFavoriteMapper mapper = mock(JobFavoriteMapper.class);
    private final JsonCodec jsonCodec = new JsonCodec();
    private final JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    private final ResumeStorageService resumeStorageService = mock(ResumeStorageService.class);
    private final BossCliService bossCliService = mock(BossCliService.class);
    private final JobFavoriteServiceImpl service =
        new JobFavoriteServiceImpl(
            mapper,
            jsonCodec,
            new JobBuddyProperties(),
            jobRuntimeService,
            resumeStorageService,
            bossCliService);
  }
}
