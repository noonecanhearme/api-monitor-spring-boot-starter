# API监控组件（api-monitor-spring-boot-starter）

一个功能强大的Spring Boot API监控组件，提供接口调用记录和火焰图生成功能，可以帮助开发者更好地监控和分析API性能。

## 许可证

本项目采用 [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0) 许可证。详情请参见 [LICENSE](LICENSE) 文件。

## 功能特性

1. **API调用日志记录**
   - 通过AOP切面自动记录Controller层接口调用
   - 支持记录请求方法、URL、IP、请求体、响应体、执行时间等信息
   - 支持日志文件和数据库两种存储方式
   - 自动创建数据库表，支持MySQL、PostgreSQL、SQL Server等主流数据库

2. **火焰图性能分析**
   - 通过注解快速为特定接口生成火焰图
   - 可视化展示方法调用栈和耗时情况
   - 帮助定位性能瓶颈

## 快速开始

### Maven依赖配置

在项目的 `pom.xml` 中添加以下依赖：

```xml
<dependency>
    <groupId>io.github.noonecanhearme</groupId>
    <artifactId>api-monitor-spring-boot-starter</artifactId>
    <version>1.0.0</version>
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

在Spring Boot应用的`application.properties`或`application.yml`中配置：

```yaml
# API监控配置
api:
  monitor:
    enabled: true
    # 日志记录方式：log（默认）或 database
    log-type: log
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
}
```

调用该接口后，火焰图数据文件将生成在配置的路径下。

## 配置说明

### 核心配置项

- `api.monitor.enabled`：是否启用API监控，默认true
- `api.monitor.log-type`：日志记录方式，可选值：log（日志文件）、database（数据库）
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

## 常见问题

1. **如何查看生成的火焰图？**
   
   组件生成的是折叠堆栈格式的文本文件，可以使用[FlameGraph](https://github.com/brendangregg/FlameGraph)工具转换为可视化的SVG格式。

2. **数据库表创建失败怎么办？**
   
   请确保数据库连接配置正确，并且用户有创建表的权限。如果仍然失败，可以手动创建表。

3. **如何自定义日志记录的内容？**
   
   可以通过配置`log-request-body`和`log-response-body`来控制是否记录请求体和响应体，也可以使用`ignore-paths`来忽略特定路径的日志记录。

## 许可证

本项目采用 [Apache License 2.0](http://www.apache.org/licenses/LICENSE-2.0) 许可证。详情请参见 [LICENSE](LICENSE) 文件。