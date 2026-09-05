package com.dbconverter.service;

import com.dbconverter.common.AiConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 流式增量解析测试。
 * <p>
 * 守的是"页面转圈好几分钟"那个 bug：服务端默认开扩展思考时，流上先跑几千条
 * {@code thinking_delta}（实测 2368 条、315 秒后才出正文）。早先的实现只认 {@code delta.text}，
 * 把这些思考增量当成"什么都没收到"，于是自带的超时死线把本来会成功的请求砍了。
 * <p>
 * 所以这里锁两件事：
 * <ol>
 *   <li>思考增量必须被识别出来（{@code thinking=true}），既不能计入最终 SQL，也不能被丢成 null——
 *       丢成 null 就等于"没进展"，死线又会误杀；</li>
 *   <li>请求体必须显式带 {@code thinking:disabled}，这是 320 秒降到 8.7 秒的关键。</li>
 * </ol>
 */
class AiStreamDeltaTest {

    private final AiService aiService = new AiService(null);

    private AiConfig anthropicConfig() {
        AiConfig config = new AiConfig();
        config.setProtocol("anthropic");
        config.setBaseUrl("https://example.com/api/plan");
        config.setApiKey("k");
        config.setModel("some-model");
        config.setMaxTokens(4096);
        return config;
    }

    // ================= Anthropic 协议 =================

    @Test
    @DisplayName("thinking_delta 被识别为思考而非正文")
    void recognisesThinkingDelta() throws Exception {
        AiService.SseDelta delta = aiService.extractAnthropicDelta(
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"先看这条 SQL\"}}");

        assertNotNull(delta, "思考增量不能被丢成 null——那会被超时死线当成没进展");
        assertTrue(delta.thinking());
        assertEquals("先看这条 SQL", delta.text());
    }

    @Test
    @DisplayName("text_delta 被识别为正文")
    void recognisesTextDelta() throws Exception {
        AiService.SseDelta delta = aiService.extractAnthropicDelta(
                "{\"type\":\"content_block_delta\",\"index\":1,"
                        + "\"delta\":{\"type\":\"text_delta\",\"text\":\"SELECT 1\"}}");

        assertNotNull(delta);
        assertFalse(delta.thinking());
        assertEquals("SELECT 1", delta.text());
    }

    @Test
    @DisplayName("signature_delta 之类既非正文也非思考，跳过")
    void skipsSignatureDelta() throws Exception {
        assertNull(aiService.extractAnthropicDelta(
                "{\"type\":\"content_block_delta\",\"index\":0,"
                        + "\"delta\":{\"type\":\"signature_delta\",\"signature\":\"abc==\"}}"));
    }

    @Test
    @DisplayName("非 content_block_delta 事件一律跳过")
    void skipsNonDeltaEvents() throws Exception {
        assertNull(aiService.extractAnthropicDelta("{\"type\":\"message_start\",\"message\":{}}"));
        assertNull(aiService.extractAnthropicDelta("{\"type\":\"content_block_stop\",\"index\":0}"));
        assertNull(aiService.extractAnthropicDelta("{\"type\":\"message_stop\"}"));
    }

    @Test
    @DisplayName("error 事件抛异常而不是静默当成没内容")
    void throwsOnErrorEvent() {
        assertThrows(java.io.IOException.class, () -> aiService.extractAnthropicDelta(
                "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"too busy\"}}"));
    }

    @Test
    @DisplayName("畸形 JSON 只跳过，不能让已生成一半的流整体失败")
    void skipsMalformedJson() throws Exception {
        assertNull(aiService.extractAnthropicDelta("这不是 JSON"));
    }

    // ================= OpenAI 协议 =================

    @Test
    @DisplayName("OpenAI 的 reasoning_content 也算思考（DeepSeek-R1 一类）")
    void recognisesOpenAiReasoning() throws Exception {
        AiService.SseDelta delta = aiService.extractOpenAiDelta(
                "{\"choices\":[{\"delta\":{\"reasoning_content\":\"嗯\"}}]}");

        assertNotNull(delta);
        assertTrue(delta.thinking());
        assertEquals("嗯", delta.text());
    }

    @Test
    @DisplayName("OpenAI 的 content 是正文")
    void recognisesOpenAiContent() throws Exception {
        AiService.SseDelta delta = aiService.extractOpenAiDelta(
                "{\"choices\":[{\"delta\":{\"content\":\"SELECT 1\"}}]}");

        assertNotNull(delta);
        assertFalse(delta.thinking());
        assertEquals("SELECT 1", delta.text());
    }

    @Test
    @DisplayName("OpenAI 空 choices 跳过")
    void skipsEmptyChoices() throws Exception {
        assertNull(aiService.extractOpenAiDelta("{\"choices\":[]}"));
    }

    // ================= 请求体 =================

    @Test
    @DisplayName("Anthropic 请求必须显式关掉扩展思考")
    void anthropicRequestDisablesThinking() throws Exception {
        String body = aiService.buildAnthropicTextRequest(anthropicConfig(), "some-model", "转这条 SQL", true);

        assertTrue(body.contains("\"thinking\""), "请求体缺少 thinking 字段: " + body);
        assertTrue(body.contains("\"disabled\""),
                "思考没被关掉，实测会让一次转换从 8.7 秒变成 320 秒: " + body);
        assertTrue(body.contains("\"stream\":true"));
    }

    @Test
    @DisplayName("非流式请求同样关掉思考")
    void nonStreamingRequestAlsoDisablesThinking() throws Exception {
        String body = aiService.buildAnthropicTextRequest(anthropicConfig(), "some-model", "转这条 SQL", false);

        assertTrue(body.contains("\"disabled\""));
        assertFalse(body.contains("\"stream\""));
    }

    // ================= prompt 的最小改动约束 =================

    @Test
    @DisplayName("prompt 要求最小改动，不能授权模型重写")
    void promptDemandsMinimalChange() {
        String prompt = aiService.buildOptimizePrompt("SELECT IFNULL(a,0) FROM t", "mysql");

        assertTrue(prompt.contains("最小改动"), "缺少最小改动约束: " + prompt);
        // 这两句是原来措辞里招致"把 10 行查询重写成上百行"的根源，不能再出现
        assertFalse(prompt.contains("优化SQL性能"), "prompt 又在要求性能优化，会招致整体重写");
        assertFalse(prompt.contains("最佳实践"), "prompt 又在要求最佳实践，会招致整体重写");
    }

    @Test
    @DisplayName("prompt 明确禁止会话变量和多语句")
    void promptForbidsSessionVariables() {
        String prompt = aiService.buildOptimizePrompt("SELECT 1", "mysql");

        assertTrue(prompt.contains("@x :="), "缺少对会话变量写法的禁止: " + prompt);
        assertTrue(prompt.contains("WITH RECURSIVE"), "缺少层次查询的正确替代写法指引");
        assertTrue(prompt.contains("#{"), "缺少 MyBatis 占位符必须保留的要求");
    }

    @Test
    @DisplayName("prompt 里带上目标数据库和待处理 SQL")
    void promptCarriesInputs() {
        String prompt = aiService.buildOptimizePrompt("SELECT NVL(a,0) FROM t WHERE id = #{x}", "dameng");

        assertTrue(prompt.contains("dameng"));
        assertTrue(prompt.contains("SELECT NVL(a,0) FROM t WHERE id = #{x}"));
    }

    // ================= 截断检测 =================

    @Test
    @DisplayName("Anthropic：message_delta 的 stop_reason=max_tokens 判定为截断")
    void detectsAnthropicTruncation() {
        assertEquals(Boolean.TRUE, aiService.detectTruncation(anthropicConfig(),
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"}}"));
    }

    @Test
    @DisplayName("Anthropic：正常结束不算截断")
    void anthropicNormalEndIsNotTruncation() {
        assertEquals(Boolean.FALSE, aiService.detectTruncation(anthropicConfig(),
                "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}"));
    }

    @Test
    @DisplayName("没有结束原因的事件返回 null，不能误判成正常结束")
    void unknownStopReasonIsNull() {
        assertNull(aiService.detectTruncation(anthropicConfig(),
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"x\"}}"));
    }

    @Test
    @DisplayName("OpenAI：finish_reason=length 判定为截断")
    void detectsOpenAiTruncation() {
        AiConfig config = anthropicConfig();
        config.setProtocol("openai");

        assertEquals(Boolean.TRUE, aiService.detectTruncation(config,
                "{\"choices\":[{\"finish_reason\":\"length\",\"delta\":{}}]}"));
        assertEquals(Boolean.FALSE, aiService.detectTruncation(config,
                "{\"choices\":[{\"finish_reason\":\"stop\",\"delta\":{}}]}"));
    }
}
