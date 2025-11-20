# API监控组件发布指南

本文档详细说明如何使用Sonatype Central Publishing Maven Plugin将项目发布到Maven中央仓库。

## 前置条件

1. JDK 21或更高版本
2. Maven 3.6.0或更高版本
3. Sonatype Central账号（https://central.sonatype.com/）
4. GPG工具已安装并配置

## 发布步骤

### 1. 配置Maven settings.xml

在您的`~/.m2/settings.xml`文件中添加以下配置（请参考`settings.xml.example`）：

```xml
<settings>
    <servers>
        <server>
            <id>central</id>
            <username>您的Sonatype用户名</username>
            <password>您的Sonatype密码</password>
        </server>
    </servers>
    
    <profiles>
        <profile>
            <id>gpg</id>
            <properties>
                <gpg.executable>gpg</gpg.executable>
                <gpg.passphrase>您的GPG密码</gpg.passphrase>
                <gpg.keyname>您的GPG密钥ID</gpg.keyname>
            </properties>
        </profile>
    </profiles>
    
    <activeProfiles>
        <activeProfile>gpg</activeProfile>
    </activeProfiles>
</settings>
```

### 2. 安装GPG工具

请参考Sonatype官方文档中的GPG安装指南：https://central.sonatype.com/publishing/gpg-signatures

### 3. 检查项目POM配置

`pom.xml`文件已配置了必要的发布插件和参数：

- 已配置`central-publishing-maven-plugin`插件
- 已设置`sonatypeCentralUrl`为`https://central.sonatype.com`
- 已启用`autoReleaseAfterClose`自动发布功能

### 4. 构建并签名项目

在项目根目录执行以下命令：

```bash
mvn clean verify -Prelease
```

这将执行以下操作：
- 清理并编译项目
- 运行测试
- 生成源代码JAR和Javadoc JAR
- 使用GPG对所有JAR文件进行签名

### 5. 发布到Sonatype Central

使用Sonatype Central Publishing Maven Plugin发布：

```bash
mvn clean deploy -Prelease
```

或者使用传统的部署方式：

```bash
mvn clean deploy -Prelease -DskipTests
```

### 6. 发布后验证

1. 登录Sonatype Central：https://central.sonatype.com/
2. 导航到发布的组件，确认发布状态
3. 在Maven中央仓库中搜索您的组件（通常需要一些时间同步）：https://search.maven.org/

## 常见问题排查

1. **认证失败**：检查settings.xml中的用户名和密码是否正确
2. **GPG签名错误**：确保GPG已正确安装，密钥已生成并发布到密钥服务器
3. **构建失败**：确保所有测试通过，代码符合JDK 21的语法要求
4. **发布延迟**：中央仓库同步可能需要一些时间，请耐心等待

## 注意事项

- 版本号一旦发布不能修改，请谨慎选择版本号
- 确保所有依赖都是合法的，可以发布到中央仓库的
- 遵循语义化版本控制规范
- 定期更新项目以修复安全漏洞

祝发布顺利！