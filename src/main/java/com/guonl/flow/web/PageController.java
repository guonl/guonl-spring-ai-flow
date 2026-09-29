package com.guonl.flow.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 页面路由（Thymeleaf 渲染）。
 */
@Controller
public class PageController {

    /** 首页 Dashboard */
    @GetMapping({"/", "/dashboard"})
    public String dashboard() {
        return "dashboard";
    }

    /** 流程库列表 */
    @GetMapping("/flows")
    public String flows() {
        return "flows";
    }

    /** 知识库管理 */
    @GetMapping("/knowledge")
    public String knowledge() {
        return "knowledge";
    }

    /** 流程编排画布（id为空表示新建） */
    @GetMapping("/flow-editor")
    public String flowEditor(@RequestParam(value = "id", required = false) String id, Model model) {
        model.addAttribute("flowId", id);
        return "flow-editor";
    }

    /** 流程运行页 */
    @GetMapping("/run/{id}")
    public String run(@PathVariable String id, Model model) {
        model.addAttribute("flowId", id);
        return "run";
    }

    /** Playground 即席体验 */
    @GetMapping("/playground")
    public String playground() {
        return "playground";
    }

    /** 运行历史 */
    @GetMapping("/runs")
    public String runs() {
        return "runs";
    }

    /** AI 助手会话页 */
    @GetMapping("/assistant")
    public String assistant() {
        return "assistant";
    }
}
