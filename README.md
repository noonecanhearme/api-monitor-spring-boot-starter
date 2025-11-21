# API监控组件（api-monitor-spring-boot-starter）

一个功能强大的Spring Boot API监控组件，提供接口调用记录和火焰图生成功能，可以帮助开发者更好地监控和分析API性能。

## 许可证

本项目采用 [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0) 许可证。详情请参见 [LICENSE](LICENSE) 文件。

## 功能特性

1. **API调用日志记录**
   - 通过AOP切面自动记录Controller层接口调用
   - 支持记录请求方法、URL、IP、请求体、响应体、执行时间等信息
   - 支持日志文件和数据库两种存储方式，用户可灵活配置
   - 自动创建数据库表，支持MySQL、PostgreSQL、SQL Server等主流数据库

2. **火焰图性能分析**
   - 通过注解快速为特定接口生成火焰图
   - 支持多种性能维度分析：CPU使用、内存分配、锁竞争、缓存未命中
   - 可视化展示方法调用栈和耗时情况
   - 支持多种输出格式：HTML、SVG、JSON
   - 灵活的采样参数配置，帮助精确定位性能瓶颈

## 快速开始

### Maven依赖配置

在项目的 `pom.xml` 中添加以下依赖：

```xml
<dependency>
    <groupId>io.github.noonecanhearme</groupId>
    <artifactId>api-monitor-spring-boot-starter</artifactId>
    <version>1.0.5</version>
</dependency>
```

### Maven仓库配置

作为开源项目，该组件已配置为发布到Maven中央仓库（通过OSSRH）。使用时无需额外配置特殊的仓库地址，Maven会自动从中央仓库下载依赖。

如果需要开发和发布此组件，开发者需要在 `~/.m2/settings.xml` 中配置以下认证信息：

```xml
<settings>
    <servers>
        <server>
            <id>ossrh</id>
            <username>your-jira-username</username>
            <password>your-jira-password</password>
        </server>
    </servers>
</settings>
```

发布到Maven中央仓库还需要GPG签名配置和Sonatype OSSRH账户，请参考[Sonatype OSSRH文档](https://central.sonatype.org/publish/publish-guide/)获取详细步骤。

### 3. 配置组件

在Spring Boot应用的配置文件(`application.properties`或`application.yml`)中添加以下配置：

```yaml
# API监控配置
api:
  monitor:
    enabled: true
    # 日志记录方式：log（默认）或 database
    log-type: log
    # 日志文件保存路径（当log-type为log时有效）
    log-file-path: ./logs/api-monitor.log
    # 是否记录请求体
    log-request-body: true
    # 是否记录响应体
    log-response-body: true
    # 忽略的URL路径
    ignore-paths: 
      - /actuator/
      - /swagger/
    # 数据库配置
    database:
      enabled: false
      # 数据库表名前缀
      table-prefix: api_
      # 是否自动创建表
      auto-create-table: true
    # 火焰图配置
    flame-graph:
      enabled: false
      # 火焰图保存路径
      save-path: ./flamegraphs
      # 火焰图采样时长（毫秒）
      sampling-duration: 1000
      # 火焰图采样率（毫秒）
      sampling-rate: 50
      # 火焰图输出格式，支持 html、svg、json
      format: html
      # 分析事件类型：CPU（默认）、ALLOC、LOCK、CACHE_MISSES
      event-type: CPU
```

### 4. 使用示例

#### 4.1 自动记录API调用日志

组件会自动记录所有Controller层的接口调用，无需额外配置。

#### 4.2 生成火焰图

在需要生成火焰图的接口方法上添加`@EnableFlameGraph`注解：

```java
import io.github.noonecanhearme.apimonitor.annotation.EnableFlameGraph;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TestController {
    
    @GetMapping("/test")
    @EnableFlameGraph(samplingDuration = 2000)
    public String test() {
        // 业务逻辑
        return "success";
    }
    
    @GetMapping("/complex")
    @EnableFlameGraph(samplingDuration = 3000, samplingRate = 20)
    public String complexOperation() {
        // 复杂业务逻辑
        return "completed";
    }
    
    @GetMapping("/memory-intensive")
    @EnableFlameGraph(samplingDuration = 2000, eventType = "ALLOC")
    public List<DataItem> memoryIntensiveOperation() {
        // 内存密集型操作
        return generateLargeDataset();
    }
    
    @GetMapping("/concurrent")
    @EnableFlameGraph(samplingDuration = 1500, eventType = "LOCK")
    public String concurrentOperation() {
        // 并发操作
        return executeConcurrentTasks();
    }
}
```

调用该接口后，火焰图文件将按照配置的格式（html、svg或json）生成在指定的保存路径下。

## 配置说明

### 核心配置项

- `api.monitor.enabled`：是否启用API监控，默认true
- `api.monitor.log-type`：日志记录方式，可选值：log（日志文件）、database（数据库）
- `api.monitor.log-file-path`：日志文件保存路径（当log-type为log时有效），默认./logs/api-monitor.log
- `api.monitor.log-request-body`：是否记录请求体，默认true
- `api.monitor.log-response-body`：是否记录响应体，默认true
- `api.monitor.ignore-paths`：需要忽略的URL路径数组

### 数据库配置

- `api.monitor.database.enabled`：是否启用数据库存储，默认false
- `api.monitor.database.table-prefix`：数据库表名前缀，默认api_
- `api.monitor.database.auto-create-table`：是否自动创建表，默认true

### 火焰图配置

- `api.monitor.flame-graph.enabled`：是否启用火焰图生成，默认false
- `api.monitor.flame-graph.save-path`：火焰图保存路径，默认./flamegraphs
- `api.monitor.flame-graph.sampling-duration`：火焰图采样时长（毫秒），默认1000
- `api.monitor.flame-graph.sampling-rate`：火焰图采样率（毫秒），默认50
- `api.monitor.flame-graph.format`：火焰图输出格式，支持html、svg、json，默认html
- `api.monitor.flame-graph.event-type`：分析事件类型，可选值：CPU（CPU使用）、ALLOC（内存分配）、LOCK（锁竞争）、CACHE_MISSES（缓存未命中），默认CPU

## 常见问题

1. **如何查看生成的火焰图？**
   
   对于HTML格式的火焰图，可以直接在浏览器中打开查看，支持以下交互功能：
   - 点击帧可以查看详细信息并高亮显示
   - 使用缩放按钮放大缩小火焰图
   - 通过事件类型下拉框筛选特定类型的帧
   - 鼠标悬停显示帧的基本信息
   
   对于其他格式，可以使用相应的工具查看。

2. **火焰图支持哪些事件类型分析？**
   
   组件支持四种主要事件类型的分析：
   - CPU：分析CPU使用率和调用耗时
   - ALLOC：分析内存分配情况
   - LOCK：分析锁竞争和线程阻塞
   - CACHE_MISSES：分析缓存未命中情况
   
   可以通过全局配置项`api.monitor.flame-graph.event-type`设置默认分析类型，或通过`@EnableFlameGraph`注解的`eventType`参数为特定接口单独配置。
   
3. **数据库表创建失败怎么办？**
   
   请确保数据库连接配置正确，并且用户有创建表的权限。如果仍然失败，可以手动创建表。

4. **如何自定义日志记录的内容？**
   
   可以通过配置`log-request-body`和`log-response-body`来控制是否记录请求体和响应体，也可以使用`ignore-paths`来忽略特定路径的日志记录。

## 许可证

本项目采用 [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0) 许可证。详情请参见 [LICENSE](LICENSE) 文件。