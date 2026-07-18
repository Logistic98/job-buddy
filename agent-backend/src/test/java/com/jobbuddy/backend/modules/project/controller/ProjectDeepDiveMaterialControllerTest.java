package com.jobbuddy.backend.modules.project.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.modules.project.service.ProjectDeepDiveService;
import com.jobbuddy.backend.modules.project.service.ProjectMaterialFile;
import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

class ProjectDeepDiveMaterialControllerTest {

  @Test
  void shouldReturnAttachmentResponseWithAuthorizedFileName() {
    ProjectDeepDiveService service = mock(ProjectDeepDiveService.class);
    HttpServletRequest request = authenticatedRequest();
    when(service.openMaterial("tenant-1", "user-1", "material-1"))
        .thenReturn(
            new ProjectMaterialFile(
                "项目资料.zip",
                "application/zip",
                4L,
                new ByteArrayInputStream(new byte[] {1, 2, 3, 4})));
    ProjectDeepDiveController controller = new ProjectDeepDiveController(service);

    ResponseEntity<Resource> attachment = controller.materialFile("material-1", request);

    assertEquals("attachment", attachment.getHeaders().getContentDisposition().getType());
    assertEquals(MediaType.valueOf("application/zip"), attachment.getHeaders().getContentType());
    assertEquals(4L, attachment.getHeaders().getContentLength());
    assertEquals("nosniff", attachment.getHeaders().getFirst("X-Content-Type-Options"));
    verify(service).openMaterial("tenant-1", "user-1", "material-1");
  }

  @Test
  void shouldStreamSelectedMaterialsAsZipAndKeepDuplicateNames() throws Exception {
    ProjectDeepDiveService service = mock(ProjectDeepDiveService.class);
    HttpServletRequest request = authenticatedRequest();
    when(service.openMaterial("tenant-1", "user-1", "material-1"))
        .thenReturn(
            new ProjectMaterialFile(
                "项目资料.txt", "text/plain", 3L, new ByteArrayInputStream("one".getBytes())));
    when(service.openMaterial("tenant-1", "user-1", "material-2"))
        .thenReturn(
            new ProjectMaterialFile(
                "项目资料.txt", "text/plain", 3L, new ByteArrayInputStream("two".getBytes())));
    ProjectDeepDiveController controller = new ProjectDeepDiveController(service);

    ResponseEntity<StreamingResponseBody> response =
        controller.batchMaterialFiles(List.of("material-1", "material-2", "material-1"), request);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    response.getBody().writeTo(output);

    assertEquals("attachment", response.getHeaders().getContentDisposition().getType());
    assertEquals(MediaType.valueOf("application/zip"), response.getHeaders().getContentType());
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(output.toByteArray()))) {
      ZipEntry first = zip.getNextEntry();
      assertEquals("项目资料.txt", first.getName());
      assertArrayEquals("one".getBytes(), zip.readAllBytes());
      ZipEntry second = zip.getNextEntry();
      assertEquals("项目资料 (2).txt", second.getName());
      assertArrayEquals("two".getBytes(), zip.readAllBytes());
    }
    verify(service).openMaterial("tenant-1", "user-1", "material-1");
    verify(service).openMaterial("tenant-1", "user-1", "material-2");
  }

  private HttpServletRequest authenticatedRequest() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    AuthenticatedUser user = new AuthenticatedUser("user-1", "tester", "Tester", "user");
    user.setTenantId("tenant-1");
    when(request.getAttribute(AuthenticatedUserContext.USER_ATTRIBUTE)).thenReturn(user);
    return request;
  }
}
