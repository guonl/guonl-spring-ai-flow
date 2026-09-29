package com.guonl.flow.ai;

import org.springframework.ai.audio.transcription.AudioTranscription;
import org.springframework.ai.audio.transcription.AudioTranscriptionPrompt;
import org.springframework.ai.audio.transcription.AudioTranscriptionResponse;
import org.springframework.ai.audio.transcription.TranscriptionModel;
import org.springframework.core.io.Resource;

/**
 * 本地模拟语音转录模型（mock模式）。
 * <p>返回确定性演示转录文本（含上传文件名标识），在无外部语音API的情况下
 * 可验证「音频上传 → 转录 → 并入流程输入 → 下游节点处理」完整链路。</p>
 */
public class MockTranscriptionModel implements TranscriptionModel {

    /** 演示转录文本：与示例流程（线索清洗）联动，便于全链路体验 */
    private static final String DEMO_TEXT =
        "请帮我登记一条新线索：联系人王小明，电话13800138000，意向产品为企业版，希望本周内安排产品演示。";

    @Override
    public AudioTranscriptionResponse call(AudioTranscriptionPrompt prompt) {
        Resource audio = prompt.getInstructions();
        String name = audio != null && audio.getFilename() != null ? audio.getFilename() : "audio";
        String text = "（模拟语音转录，文件：" + name + "）" + DEMO_TEXT;
        return new AudioTranscriptionResponse(new AudioTranscription(text));
    }
}
