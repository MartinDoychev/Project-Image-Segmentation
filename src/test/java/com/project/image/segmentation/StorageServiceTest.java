package com.project.image.segmentation;

import com.project.image.segmentation.exceptions.StorageException;
import com.project.image.segmentation.service.StorageService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.*;

class StorageServiceTest {
    @Test
    void store_and_storeResultImage_work() throws Exception {
        Path tmp = Files.createTempDirectory("uploads-test");
        StorageService storage = new StorageService(tmp.toString());

        MockMultipartFile file = new MockMultipartFile(
                "file", "test.png", "image/png", new byte[]{1,2,3,4}
        );
        var stored = storage.store(file);
        assertThat(Files.exists(stored.path())).isTrue();

        var res = storage.storeResultImage(new byte[]{8,9,10});
        assertThat(Files.exists(res.path())).isTrue();
    }
    @Test
    void store_rejectsNonImage() {
        Path tmp = Path.of(System.getProperty("java.io.tmpdir"), "uploads-test2");
        StorageService storage = new StorageService(tmp.toString());

        MockMultipartFile notImage = new MockMultipartFile("file", "x.txt", "text/plain", "hi".getBytes());
        assertThatThrownBy(() -> storage.store(notImage)).isInstanceOf(StorageException.class);
    }
}