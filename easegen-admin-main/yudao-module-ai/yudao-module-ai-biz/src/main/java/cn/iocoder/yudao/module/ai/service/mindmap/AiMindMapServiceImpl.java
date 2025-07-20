package cn.iocoder.yudao.module.ai.service.mindmap;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.Assert;
import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import cn.iocoder.yudao.framework.common.pojo.CommonResult;
import cn.iocoder.yudao.framework.common.pojo.PageResult;
import cn.iocoder.yudao.framework.common.util.object.BeanUtils;
import cn.iocoder.yudao.framework.tenant.core.util.TenantUtils;
import cn.iocoder.yudao.module.ai.controller.admin.mindmap.vo.AiMindMapGenerateReqVO;
import cn.iocoder.yudao.module.ai.controller.admin.mindmap.vo.AiMindMapPageReqVO;
import cn.iocoder.yudao.module.ai.dal.dataobject.mindmap.AiMindMapDO;
import cn.iocoder.yudao.module.ai.dal.mysql.mindmap.AiMindMapMapper;
import cn.iocoder.yudao.module.ai.enums.ErrorCodeConstants;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static cn.iocoder.yudao.framework.common.exception.util.ServiceExceptionUtil.exception;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.error;
import static cn.iocoder.yudao.framework.common.pojo.CommonResult.success;
import static cn.iocoder.yudao.module.ai.enums.ErrorCodeConstants.MIND_MAP_NOT_EXISTS;

/**
 * AI 思维导图 Service 实现类
 *
 * @author xiaoxin
 */
@Service
@Slf4j
public class AiMindMapServiceImpl implements AiMindMapService {

    @Resource
    private AiMindMapMapper mindMapMapper;

    // DeepSeek API配置
    private static final String DEEPSEEK_API_URL = "https://api.deepseek.com/chat/completions";
    private static final String DEEPSEEK_API_KEY = "sk-7abb599ec13649babd5a5a807f01b1cc";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";

    @Override
    public Flux<CommonResult<String>> generateMindMap(AiMindMapGenerateReqVO generateReqVO, Long userId) {
        // 1. 插入思维导图信息
        log.info("[AI思维导图] 即将插入的 userId: {}", userId);
        AiMindMapDO mindMapDO = BeanUtils.toBean(generateReqVO, AiMindMapDO.class);
        mindMapDO.setUserId(userId);
        mindMapDO.setModel(DEEPSEEK_MODEL);
        mindMapDO.setPlatform("deepseek");
        mindMapMapper.insert(mindMapDO);

        // 2. 构建请求参数
        String systemMessage = "你是一位专业的思维导图生成助手。你的任务是：\n" +
                "1. 根据用户提供的主题，严格按照以下格式模板生成思维导图\n" +
                "2. 使用Markdown格式输出，节点层级用 #、##、###、#### 表示\n" +
                "3. 必须严格按照以下格式模板，不要添加任何说明文字\n" +
                "4. 直接开始输出思维导图内容，不要有任何前缀\n" +
                "5. 使用中文输出，内容要全面且准确\n" +
                "6. 格式模板如下：\n" +
                "   # [主题名称]\n" +
                "   ## [一级分类1]\n" +
                "   ### [二级分类1.1]\n" +
                "   ### [二级分类1.2]\n" +
                "   ## [一级分类2]\n" +
                "   ### [二级分类2.1]\n" +
                "   #### [具体内容2.1.1]\n" +
                "   #### [具体内容2.1.2]\n" +
                "   ### [二级分类2.2]\n" +
                "   ## [一级分类3]\n" +
                "   ### [二级分类3.1]\n" +
                "   ### [二级分类3.2]\n" +
                "   7. 重要：严格按照上述格式，每个层级清晰分组，同级内容一列一列排列\n" +
                "   8. 重要：不要输出任何'思维导图'、'以下是'等说明文字，直接开始内容\n" +
                "   9. 重要：确保每个分支都有适当的内容，避免空分支";

        String userPrompt = generateReqVO.getPrompt();
        // 如果用户输入的是简单主题词（如"Java学习路线"），则拼接成完整prompt
        if (isSimpleTopic(userPrompt)) {
            userPrompt = "请以" + userPrompt + "为主题，严格按照以下格式模板生成思维导图，使用Markdown格式，节点层级用 #、##、###、#### 表示。要求：\n" +
                    "1. 严格按照格式模板，每个层级清晰分组\n" +
                    "2. 同级内容一列一列排列\n" +
                    "3. 不要添加任何说明性文字，只输出思维导图内容本身\n" +
                    "4. 确保每个分支都有适当的内容，避免空分支\n" +
                    "5. 直接开始输出思维导图内容，不要有任何前缀说明\n" +
                    "6. 格式模板：\n" +
                    "   # [主题名称]\n" +
                    "   ## [一级分类1]\n" +
                    "   ### [二级分类1.1]\n" +
                    "   ### [二级分类1.2]\n" +
                    "   ## [一级分类2]\n" +
                    "   ### [二级分类2.1]\n" +
                    "   #### [具体内容2.1.1]\n" +
                    "   #### [具体内容2.1.2]";
        }

        log.info("1[AI思维导图] 用户输入: {}", userPrompt);

        // 3. 构建请求体
        JSONObject requestBody = new JSONObject();
        requestBody.set("model", DEEPSEEK_MODEL);
        requestBody.set("stream", true);
        requestBody.set("temperature", 0.7);
        requestBody.set("max_tokens", 4000);

        JSONArray messages = new JSONArray();
        messages.add(new JSONObject().set("role", "system").set("content", systemMessage));
        messages.add(new JSONObject().set("role", "user").set("content", userPrompt));
        requestBody.set("messages", messages);

        log.info("2[AI思维导图] 请求体: {}", requestBody.toString());

        // 4. 发送流式请求
        return Flux.<CommonResult<String>>create(sink -> {
            try {
                HttpResponse response = HttpRequest.post(DEEPSEEK_API_URL)
                        .header("Content-Type", "application/json")
                        .header("Authorization", "Bearer " + DEEPSEEK_API_KEY)
                        .body(requestBody.toString())
                        .execute();

                if (!response.isOk()) {
                    log.error("[AI思维导图] DeepSeek API调用失败: {}", response.body());
                    sink.error(new RuntimeException("DeepSeek API调用失败: " + response.body()));
                    return;
                }

                String responseBody = response.body();
                log.info("3[AI思维导图] 开始处理流式响应");

                // 处理流式响应
                StringBuffer contentBuffer = new StringBuffer();
                String[] lines = responseBody.split("\n");
                
                for (String line : lines) {
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if ("[DONE]".equals(data)) {
                            break;
                        }
                        
                        try {
                            JSONObject jsonData = JSONUtil.parseObj(data);
                            JSONArray choices = jsonData.getJSONArray("choices");
                            if (choices != null && choices.size() > 0) {
                                JSONObject choice = choices.getJSONObject(0);
                                JSONObject delta = choice.getJSONObject("delta");
                                if (delta != null) {
                                    String content = delta.getStr("content");
                                    if (StrUtil.isNotBlank(content)) {
                                        log.info("[AI思维导图] AI返回内容chunk: {}", content);
                                        contentBuffer.append(content);
                                        sink.next(success(content));
                                    }
                                }
                            }
                        } catch (Exception e) {
                            log.warn("[AI思维导图] 解析响应数据失败: {}", data, e);
                        }
                    }
                }

                // 完成时更新数据库
                String finalContent = contentBuffer.toString();
                log.info("[AI思维导图] 生成完成，总内容长度: {}", finalContent.length());
                
                // 忽略租户，因为 Flux 异步无法透传租户
                TenantUtils.executeIgnore(() ->
                        mindMapMapper.updateById(new AiMindMapDO().setId(mindMapDO.getId()).setGeneratedContent(finalContent)));
                
                sink.complete();
                
            } catch (Exception e) {
                log.error("[AI思维导图] 生成过程中发生异常", e);
                // 忽略租户，因为 Flux 异步无法透传租户
                TenantUtils.executeIgnore(() ->
                        mindMapMapper.updateById(new AiMindMapDO().setId(mindMapDO.getId()).setErrorMessage(e.getMessage())));
                sink.error(e);
            }
        }, reactor.core.publisher.FluxSink.OverflowStrategy.BUFFER).onErrorResume(error -> Flux.just(error(ErrorCodeConstants.WRITE_STREAM_ERROR)));
    }

    /**
     * 判断是否为简单主题词
     */
    private static boolean isSimpleTopic(String prompt) {
        if (StrUtil.isBlank(prompt)) {
            return false;
        }
        
        // 如果prompt长度小于30且不包含"请"、"生成"、"思维导图"等关键词，认为是简单主题词
        return prompt.length() <= 30 && 
               !prompt.contains("请") && 
               !prompt.contains("生成") && 
               !prompt.contains("思维导图") &&
               !prompt.contains("Markdown") &&
               !prompt.contains("格式");
    }

    @Override
    public void deleteMindMap(Long id) {
        // 校验存在
        validateMindMapExists(id);
        // 删除
        mindMapMapper.deleteById(id);
    }

    private void validateMindMapExists(Long id) {
        if (mindMapMapper.selectById(id) == null) {
            throw exception(MIND_MAP_NOT_EXISTS);
        }
    }

    @Override
    public PageResult<AiMindMapDO> getMindMapPage(AiMindMapPageReqVO pageReqVO) {
        return mindMapMapper.selectPage(pageReqVO);
    }
}
