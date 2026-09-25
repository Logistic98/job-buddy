package com.jobbuddy.backend.modules.chat.storage;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

class ChatAttachmentStorageTest {
  @Test
  void uploadCreatesMissingBucketAndUsesRequestedObject() throws Exception {
    MinioClient client = mock(MinioClient.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getMinio().setBucket("test-attachments");
    ChatAttachmentStorage storage = new ChatAttachmentStorage(properties, client);
    storage.upload(
        new MockMultipartFile("file", new byte[] {1}), "owner/session/file", "text/plain");
    verify(client).makeBucket(any());
    ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
    verify(client).putObject(args.capture());
    assertEquals("test-attachments", args.getValue().bucket());
    assertEquals("owner/session/file", args.getValue().object());
    storage.delete(" ");
    verify(client, never()).removeObject(any());
    storage.delete("owner/session/file");
    verify(client).removeObject(any());
  }

  @Test
  void dependencyErrorsArePropagatedWithCause() throws Exception {
    MinioClient client = mock(MinioClient.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getMinio().setBucket("test-attachments");
    ChatAttachmentStorage storage = new ChatAttachmentStorage(properties, client);
    when(client.bucketExists(any())).thenReturn(true);
    when(client.putObject(any())).thenThrow(new IOException("offline"));
    IOException error =
        assertThrows(
            IOException.class,
            () ->
                storage.upload(
                    new MockMultipartFile("file", new byte[] {1}), "owner/file", "text/plain"));
    assertEquals("offline", error.getCause().getMessage());
    verify(client, never()).makeBucket(any());
    doThrow(new IOException("offline")).when(client).removeObject(any());
    assertEquals(
        "offline",
        assertThrows(IllegalStateException.class, () -> storage.delete("owner/file"))
            .getCause()
            .getMessage());
  }
}
