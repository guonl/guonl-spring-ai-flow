package com.guonl.flow.web;

import com.guonl.flow.ai.AiInvoker;
import com.guonl.flow.config.FlowProperties;
import com.guonl.flow.core.engine.FlowInput;
import com.guonl.flow.excel.ExcelParser;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 流程输入组装器：将 HTTP 上传的文本/图片/Excel/音频 统一装配为引擎输入。
 * <p>音频（语音输入）经转录模型（Spring AI {@link TranscriptionModel} SPI）转为文字后并入text，
 * 下游节点无感知地以普通文本使用。</p>
 */
@Component
@RequiredArgsConstructor
public class InputAssembler {

    private final ExcelParser excelParser;

    private final FlowProperties flowProperties;

    private final ObjectProvider<TranscriptionModel> transcriptionModelProvider;

    public FlowInput assemble(String text, String imageUrl, List<MultipartFile> images,
                              MultipartFile excel, MultipartFile audio, String conversationId) throws IOException {
        FlowInput.FlowInputBuilder builder = FlowInput.builder();
        // 音频转录并入文本（语音输入场景）
        if (audio != null && !audio.isEmpty()) {
            String transcript = transcribe(audio);
            text = StringUtils.hasText(text) ? text.trim() + "\n" + transcript : transcript;
        }
        if (StringUtils.hasText(text)) {
            builder.text(text);
        }
        if (StringUtils.hasText(conversationId)) {
            builder.conversationId(conversationId.trim());
        }
        List<AiInvoker.MediaItem> medias = new ArrayList<>();
        if (StringUtils.hasText(imageUrl)) {
            medias.add(AiInvoker.MediaItem.ofUrl("external-image", "image/png", imageUrl.trim()));
        }
        if (images != null) {
            for (MultipartFile file : images) {
                if (file != null && !file.isEmpty()) {
                    medias.add(AiInvoker.MediaItem.ofBytes(file.getOriginalFilename(),
                        file.getContentType(), file.getBytes()));
                }
            }
        }
        builder.images(medias);
        if (excel != null && !excel.isEmpty()) {
            builder.excel(excelParser.parse(excel.getBytes(), flowProperties.getExcel().getMaxRows()));
        }
        return builder.build();
    }

    /** 调用转录模型转写音频（文件名随Resource传入，OpenAI等实现依赖文件扩展名识别格式） */
    private String transcribe(MultipartFile audio) throws IOException {
        TranscriptionModel model = transcriptionModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("语音转录模型未装配（TranscriptionModel）。"
                + "mock模式自动可用；llm模式需启用 spring.ai.model.audio.transcription=openai 并配置语音端点");
        }
        byte[] bytes = audio.getBytes();
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return audio.getOriginalFilename();
            }
        };
        String transcript = model.call(new AudioTranscriptionPrompt(resource)).getResult().getOutput();
        if (transcript == null || transcript.isBlank()) {
            throw new IllegalStateException("语音转录结果为空，请确认音频文件有效");
        }
        return transcript.trim();
    }

    /** Excel 上传项解析（Playground 等场景单独取用时） */
    public ExcelParser.ExcelData parseExcel(MultipartFile excel) throws IOException {
        return excelParser.parse(excel.getBytes(), flowProperties.getExcel().getMaxRows());
    }
}
