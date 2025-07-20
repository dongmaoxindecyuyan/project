<template>
  <div class="w-[350px] p-5 flex flex-col bg-[#f5f7f9]">
    <h3 class="w-full h-7 text-5 text-center leading-[28px] title">思维导图创作中心</h3>
    <div class="flex-grow overflow-y-auto">
      <div class="mt-[30px]">
        <el-text tag="b">您的需求？</el-text>
        <el-input
          v-model="formData.prompt"
          maxlength="1024"
          :rows="5"
          class="w-100% mt-15px"
          input-style="border-radius: 7px;"
          placeholder="请输入提示词，让AI帮你完善"
          show-word-limit
          type="textarea"
        />
        <el-button
          class="!w-full mt-[15px]"
          type="primary"
          :loading="isGenerating"
          @click="handleSubmit"
        >
          智能生成思维导图
        </el-button>
      </div>
      <div class="mt-[30px]">
        <el-text tag="b">使用已有内容生成？</el-text>
        <el-input
          v-model="rawContent"
          maxlength="4096"
          :rows="15"
          class="w-100% mt-15px"
          input-style="border-radius: 7px;"
          placeholder="请粘贴或编辑思维导图内容，每一行一个分支"
          show-word-limit
          type="textarea"
        />
        <el-button
          class="!w-full mt-[15px]"
          type="primary"
          @click="handleDirectGenerate"
          :disabled="isGenerating"
        >
          直接生成
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, defineProps } from 'vue'

const emits = defineEmits(['submit', 'directGenerate'])

const props = defineProps<{ isGenerating: boolean }>()

// 表单数据
const formData = reactive({
  prompt: ''
})

// 默认内容：Java 技术栈
const defaultMindMap = `# Java 技术栈

## 核心技术
### Java SE
### Java EE

## 框架
### Spring
#### Spring Boot
#### Spring MVC
#### Spring Data
### Hibernate
### MyBatis

## 构建工具
### Maven
### Gradle

## 版本控制
### Git
### SVN

## 测试工具
### JUnit
### Mockito
### Selenium

## 应用服务器
### Tomcat
### Jetty
### WildFly

## 数据库
### MySQL
### PostgreSQL
### Oracle
### MongoDB

## 消息队列
### Kafka
### RabbitMQ
### ActiveMQ

## 微服务
### Spring Cloud
### Dubbo

## 容器化
### Docker
### Kubernetes

## 云服务
### AWS
### Azure
### Google Cloud

## 开发工具
### IntelliJ IDEA
### Eclipse
### Visual Studio Code`

// 原始内容（可编辑）
const rawContent = ref(defaultMindMap)

// 处理智能生成
function handleSubmit() {
  if (!formData.prompt.trim()) return
  emits('submit', formData)
}

// 处理直接生成
function handleDirectGenerate() {
  if (!rawContent.value.trim()) return
  // 1. 在每个#前加换行
  let formatted = rawContent.value.replace(/(#+)/g, '\n$1');
  // 2. 去除开头多余换行
  formatted = formatted.replace(/^\n+/, '');
  // 3. 保证#后有空格
  formatted = formatted.replace(/(#+)([^\s#])/g, '$1 $2');
  // 4. 合并多余空行
  formatted = formatted.replace(/\n{2,}/g, '\n');
  // 强制刷新
  rawContent.value = '';
  setTimeout(() => {
    rawContent.value = formatted;
    emits('directGenerate', rawContent.value)
  }, 0);
}

defineExpose({
  setGeneratedContent(newContent: string) {
    rawContent.value = newContent
  },
  getGeneratedContent() {
    return rawContent.value
  }
})
</script>

<style lang="scss" scoped>
.title {
  color: var(--el-color-primary);
  font-weight: bold;
}

.el-textarea__inner {
  min-height: 120px;
  font-family: monospace;
  white-space: pre;
}
</style> 