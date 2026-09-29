package com.guonl.flow.ai;

import org.springframework.ai.image.Image;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * 本地模拟图片生成模型（mock模式）。
 * <p>按提示词确定性生成一张SVG占位图（data URL）：背景色由提示词散列决定，
 * 图上嵌入提示词文本，保证同提示词产出相同图片、不同提示词产出可区分，
 * 在无外部图像API的情况下可验证「提示词 → 图片 → 下游引用」完整链路，
 * 同时避免真实图片造成存储膨胀。</p>
 */
public class MockImageModel implements ImageModel {

    private static final int SIZE = 512;

    /** 每行字符数与最大行数（超出截断） */
    private static final int CHARS_PER_LINE = 14;
    private static final int MAX_LINES = 3;

    @Override
    public ImageResponse call(ImagePrompt request) {
        String prompt = request.getInstructions().isEmpty() || request.getInstructions().get(0).getText() == null
            ? ""
            : request.getInstructions().get(0).getText();
        String svg = renderSvg(prompt);
        String dataUrl = "data:image/svg+xml;base64,"
            + Base64.getEncoder().encodeToString(svg.getBytes(StandardCharsets.UTF_8));
        return new ImageResponse(List.of(new ImageGeneration(new Image(dataUrl, null))));
    }

    /** 生成SVG占位图：渐变背景（色相由提示词散列决定）+ 居中提示词文本 + 角标说明 */
    private String renderSvg(String prompt) {
        int hue = Math.floorMod(prompt.hashCode(), 360);
        int hue2 = (hue + 45) % 360;
        List<String> lines = wrap(prompt.trim(), CHARS_PER_LINE, MAX_LINES);
        StringBuilder textEls = new StringBuilder();
        int lineHeight = 30;
        int startY = SIZE / 2 - (lines.size() - 1) * lineHeight / 2;
        for (int i = 0; i < lines.size(); i++) {
            textEls.append(String.format(
                "<text x='%d' y='%d' text-anchor='middle' font-family='sans-serif' font-size='24' fill='#ffffff'>%s</text>",
                SIZE / 2, startY + i * lineHeight, escapeXml(lines.get(i))));
        }
        return String.format("""
            <svg xmlns='http://www.w3.org/2000/svg' width='%d' height='%d'>
              <defs><linearGradient id='g' x1='0' y1='0' x2='1' y2='1'>
                <stop offset='0%%' stop-color='hsl(%d,72%%,58%%)'/>
                <stop offset='100%%' stop-color='hsl(%d,72%%,42%%)'/>
              </linearGradient></defs>
              <rect width='%d' height='%d' fill='url(#g)'/>
              %s
              <text x='%d' y='%d' text-anchor='middle' font-family='sans-serif' font-size='13' fill='rgba(255,255,255,0.75)'>MockImageModel · 模拟生成图</text>
            </svg>""", SIZE, SIZE, hue, hue2, SIZE, SIZE, textEls, SIZE / 2, SIZE - 22);
    }

    /** 提示词按固定宽度折行（最多MAX_LINES行，超出以…截断） */
    private List<String> wrap(String text, int width, int maxLines) {
        if (text.isEmpty()) {
            return List.of("（空提示词）");
        }
        List<String> lines = new java.util.ArrayList<>();
        for (int i = 0; i < text.length() && lines.size() < maxLines; i += width) {
            lines.add(text.substring(i, Math.min(text.length(), i + width)));
        }
        if (text.length() > maxLines * width) {
            String last = lines.get(lines.size() - 1);
            lines.set(lines.size() - 1, last.substring(0, Math.max(1, width - 1)) + "…");
        }
        return lines;
    }

    /** XML特殊字符转义（SVG文本节点） */
    private String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("'", "&apos;").replace("\"", "&quot;");
    }
}
