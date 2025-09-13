package com.project.image.segmentation;

import com.project.image.segmentation.service.SegmentationService;
import com.project.image.segmentation.service.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest
@AutoConfigureMockMvc
class SegmentationControllerTest {

    @Autowired MockMvc mvc;
    @Autowired SegmentationService segmentationService;
    @Autowired StorageService storageService;

    @BeforeEach
    void setup() throws Exception {
        Files.createDirectories(Path.of("uploads"));
    }

    @Test
    @WithMockUser(username = "user", roles = {"USER"})
    void segment_flow_works() throws Exception {
        MockMultipartFile img = new MockMultipartFile(
                "file","a.png","image/png", new byte[]{(byte)0x89,'P','N','G'}
        );

        mvc.perform(multipart("/segment")
                        .file(img)
                        .param("minRegionSize","50")
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isOk())
                .andExpect(view().name("result"))
                .andExpect(model().attributeExists("segments","threshold"));
    }
    @Test
    void unauthenticated_isRedirectedToLogin() throws Exception {
        mvc.perform(get("/segment"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", org.hamcrest.Matchers.containsString("/login")));
    }
}