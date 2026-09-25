package com.jobbuddy.backend.contract.security;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.modules.interview.controller.InterviewController;
import com.jobbuddy.backend.modules.interview.dto.request.*;
import com.jobbuddy.backend.modules.interview.dto.response.*;
import com.jobbuddy.backend.modules.interview.service.InterviewDocumentTextExtractor;
import com.jobbuddy.backend.modules.interview.service.InterviewService;
import com.jobbuddy.backend.modules.project.controller.ProjectDeepDiveController;
import com.jobbuddy.backend.modules.project.dto.request.*;
import com.jobbuddy.backend.modules.project.dto.response.ProjectResponse;
import com.jobbuddy.backend.modules.project.service.ProjectDeepDiveService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;

class BusinessControllerIdentityTest {
  final InterviewService interview = mock(InterviewService.class);
  final InterviewDocumentTextExtractor extractor = mock(InterviewDocumentTextExtractor.class);
  final InterviewController interviews = new InterviewController(interview, extractor);
  final ProjectDeepDiveService project = mock(ProjectDeepDiveService.class);
  final ProjectDeepDiveController projects = new ProjectDeepDiveController(project);
  final MockHttpServletRequest request = new MockHttpServletRequest();

  BusinessControllerIdentityTest() {
    AuthenticatedUser user = new AuthenticatedUser("owner", "tester", "Tester", "user");
    user.setTenantId("tenant");
    request.setAttribute(AuthenticatedUserContext.USER_ATTRIBUTE, user);
    request.setParameter("userId", "attacker");
    request.setParameter("tenantId", "other");
  }

  @Test
  void missingIdentityStopsBothServices() {
    var anonymous = new MockHttpServletRequest();
    assertThrows(IllegalArgumentException.class, () -> projects.projects(anonymous));
    assertThrows(IllegalArgumentException.class, () -> interviews.exams(anonymous));
    verifyNoInteractions(project, interview);
  }

  @Test
  void projectCrudUsesAuthenticatedOwnerAndReturnsSavedResource() {
    ProjectRequest payload = new ProjectRequest();
    ProjectResponse saved = new ProjectResponse();
    saved.setProjectId("project");
    when(project.saveProject("tenant", "owner", payload, null)).thenReturn(saved);
    when(project.saveProject("tenant", "owner", payload, "project")).thenReturn(saved);
    when(project.getProject("tenant", "owner", "project")).thenReturn(saved);
    when(project.listProjects("tenant", "owner")).thenReturn(List.of(saved));
    assertSame(saved, projects.createProject(payload, request).getData());
    assertSame(saved, projects.updateProject("project", payload, request).getData());
    assertSame(saved, projects.project("project", request).getData());
    assertEquals(List.of(saved), projects.projects(request).getData());
    assertEquals(200, projects.deleteProject("project", request).getCode());
    verify(project).deleteProject("tenant", "owner", "project");
  }

  @Test
  void projectQuestionsAndUploadsNeverUseCallerSuppliedOwner() throws Exception {
    ProjectQuestionRequest question = new ProjectQuestionRequest();
    ProjectQuestionImportRequest imported = new ProjectQuestionImportRequest();
    ProjectQuestionGenerateRequest generation = new ProjectQuestionGenerateRequest();
    MockMultipartFile file = new MockMultipartFile("file", new byte[] {1});
    assertEquals(200, projects.addMaterial("project", file, request).getCode());
    assertEquals(200, projects.addQuestion("project", question, request).getCode());
    assertEquals(200, projects.updateQuestion("question", question, request).getCode());
    assertEquals(200, projects.importQuestions("project", imported, request).getCode());
    assertEquals(200, projects.generate("project", generation, request).getCode());
    projects.deleteMaterial("material", request);
    projects.deleteQuestion("question", request);
    verify(project).addMaterial("tenant", "owner", "project", file);
    verify(project).addQuestion("tenant", "owner", "project", question);
    verify(project).updateQuestion("tenant", "owner", "question", question);
    verify(project).importQuestions("tenant", "owner", "project", imported);
    verify(project).generateQuestions("tenant", "owner", "project", generation);
    verify(project).deleteMaterial("tenant", "owner", "material");
    verify(project).deleteQuestion("tenant", "owner", "question");
  }

  @Test
  void questionPagingAndWritesPreserveFiltersAndAuthenticatedScope() {
    InterviewQuestionPageResponse page = new InterviewQuestionPageResponse();
    when(interview.pageQuestions("tenant", "owner", "Java", "technical", "core", "hard", 2, 10))
        .thenReturn(page);
    assertSame(
        page, interviews.questions("Java", "technical", "core", "hard", 2, 10, request).getData());
    interviews.questionMeta("technical", request);
    InterviewQuestionRequest question = new InterviewQuestionRequest();
    InterviewImportRequest imported = new InterviewImportRequest();
    InterviewBatchRequest batch = new InterviewBatchRequest();
    interviews.createQuestion(question, request);
    interviews.updateQuestion("q", question, request);
    interviews.importQuestions(imported, request);
    interviews.batchQuestions(batch, request);
    interviews.deleteQuestion("q", request);
    verify(interview).questionMeta("tenant", "owner", "technical");
    verify(interview).saveQuestion("tenant", "owner", question, null);
    verify(interview).saveQuestion("tenant", "owner", question, "q");
    verify(interview).importQuestions("tenant", "owner", imported);
    verify(interview).batchQuestions("tenant", "owner", batch);
    verify(interview).deleteQuestion("tenant", "owner", "q");
  }

  @Test
  void practiceLifecyclePreservesOwnerAndExamIdentity() {
    InterviewExamRequest random = new InterviewExamRequest();
    InterviewSmartExamRequest smart = new InterviewSmartExamRequest();
    InterviewExamSubmitRequest answers = new InterviewExamSubmitRequest();
    InterviewExamResponse exam = new InterviewExamResponse();
    when(interview.getExam("tenant", "owner", "exam")).thenReturn(exam);
    when(interview.listExams("tenant", "owner")).thenReturn(List.of(exam));
    assertSame(exam, interviews.exam("exam", request).getData());
    assertEquals(List.of(exam), interviews.exams(request).getData());
    interviews.randomExam(random, request);
    interviews.smartExam(smart, request);
    interviews.submitExam("exam", answers, request);
    interviews.deleteExam("exam", request);
    verify(interview).createRandomExam("tenant", "owner", random);
    verify(interview).createSmartExam("tenant", "owner", smart);
    verify(interview).submitExam("tenant", "owner", "exam", answers);
    verify(interview).deleteExam("tenant", "owner", "exam");
  }

  @Test
  void generationCodeAndExtractionDelegatePayloadWithoutSwallowingErrors() {
    InterviewGenerateRequest generation = new InterviewGenerateRequest();
    InterviewCodeRunRequest code = new InterviewCodeRunRequest();
    MockMultipartFile file = new MockMultipartFile("file", new byte[] {1});
    interviews.generateQuestions(generation);
    interviews.runCode(code);
    interviews.extractDocumentText(file);
    verify(interview).generateQuestions(generation);
    verify(interview).runCode(code);
    verify(extractor).extract(file);
    when(interview.runCode(code)).thenThrow(new IllegalArgumentException("invalid language"));
    assertEquals(
        "invalid language",
        assertThrows(IllegalArgumentException.class, () -> interviews.runCode(code)).getMessage());
  }
}
