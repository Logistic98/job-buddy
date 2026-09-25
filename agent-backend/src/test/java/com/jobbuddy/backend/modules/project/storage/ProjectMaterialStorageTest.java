package com.jobbuddy.backend.modules.project.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import io.minio.*;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class ProjectMaterialStorageTest {
  private final JobBuddyProperties properties = new JobBuddyProperties();
  private final MinioClient client = mock(MinioClient.class);
  private final ProjectMaterialStorage storage = new ProjectMaterialStorage(properties, client);

  ProjectMaterialStorageTest() {
    properties.getMinio().setBucket("test-materials");
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void uploadCreatesBucketOnlyWhenMissing(boolean exists) throws Exception {
    when(client.bucketExists(any())).thenReturn(exists);
    var file = new MockMultipartFile("file", "sample.txt", "text/plain", new byte[] {1, 2});
    storage.upload(file, "owner/project/sample.txt", "text/plain");
    verify(client, times(exists ? 0 : 1)).makeBucket(any());
    ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
    verify(client).putObject(args.capture());
    assertEquals("test-materials", args.getValue().bucket());
    assertEquals("owner/project/sample.txt", args.getValue().object());
    assertEquals(2, args.getValue().objectSize());
  }

  @Test
  void uploadFailureRetainsCauseAndObjectName() throws Exception {
    when(client.bucketExists(any())).thenThrow(new IOException("offline"));
    var file = new MockMultipartFile("file", new byte[] {1});
    IOException error =
        assertThrows(IOException.class, () -> storage.upload(file, "owner/sample", "text/plain"));
    assertTrue(error.getMessage().contains("owner/sample"));
    assertEquals("offline", error.getCause().getCause().getMessage());
    verify(client, never()).putObject(any());
  }

  @Test
  void openAndDeleteUseConfiguredBucketAndPreserveFailureCause() throws Exception {
    GetObjectResponse response = mock(GetObjectResponse.class);
    when(client.getObject(any())).thenReturn(response);
    assertSame(response, storage.open("owner/sample"));
    ArgumentCaptor<GetObjectArgs> get = ArgumentCaptor.forClass(GetObjectArgs.class);
    verify(client).getObject(get.capture());
    assertEquals("test-materials", get.getValue().bucket());
    assertEquals("owner/sample", get.getValue().object());
    storage.delete(null);
    storage.delete(" ");
    verify(client, never()).removeObject(any());
    storage.delete("owner/sample");
    verify(client).removeObject(any());
    when(client.getObject(any())).thenThrow(new IOException("offline"));
    assertEquals(
        "offline",
        assertThrows(IllegalStateException.class, () -> storage.open("owner/sample"))
            .getCause()
            .getMessage());
    doThrow(new IOException("offline")).when(client).removeObject(any());
    assertEquals(
        "offline",
        assertThrows(IllegalStateException.class, () -> storage.delete("owner/sample"))
            .getCause()
            .getMessage());
  }
}
