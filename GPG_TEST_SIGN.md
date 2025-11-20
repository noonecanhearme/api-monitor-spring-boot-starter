# 测试GPG签名功能

## 1. 准备工作
在测试GPG签名功能之前，请确保：
- 已成功安装GPG工具
- 已生成GPG密钥对
- 已将公钥发布到密钥服务器
- 已更新settings.xml中的GPG配置（密钥ID和密码短语）

## 2. 测试GPG签名

### 2.1 测试签名命令
在项目根目录下运行以下Maven命令来测试GPG签名功能：

```bash
# 测试GPG签名，不实际发布
mvn clean verify -DskipTests
```

或者使用更明确的签名命令：

```bash
# 仅执行打包和签名阶段
mvn clean package source:jar javadoc:jar gpg:sign
```

### 2.2 验证签名是否成功
如果签名成功，您应该会在控制台输出中看到类似以下的信息：

```
[INFO] --- maven-gpg-plugin:3.0.1:sign (sign-artifacts) @ api-monitor-spring-boot-starter ---
[INFO] Signing 10 files with key ID ABCDEF123...
[INFO] Signing completed successfully.
```

## 3. 常见问题及解决方案

### 3.1 找不到GPG可执行文件
错误信息：`gpg: command not found`

解决方案：
- 确保GPG已正确安装
- 验证环境变量PATH中是否包含GPG的bin目录
- 或者在settings.xml中指定完整的GPG路径：
  ```xml
  <gpg.executable>C:\Program Files (x86)\GnuPG\bin\gpg.exe</gpg.executable>
  ```

### 3.2 密钥ID错误
错误信息：`gpg: key ABCDEF12 not found: No secret key`

解决方案：
- 确保在settings.xml中使用的是正确的密钥ID
- 运行`gpg --list-secret-keys --keyid-format LONG`确认您的密钥ID

### 3.3 密码短语错误
错误信息：`gpg: signing failed: Bad passphrase`

解决方案：
- 确保在settings.xml中输入的密码短语与生成密钥时设置的完全一致
- 注意区分大小写和特殊字符

### 3.4 代理问题
错误信息：连接密钥服务器超时

解决方案：
- 确保代理配置正确
- 可以暂时禁用代理进行测试

## 4. 注意事项
- 在生产环境中，不建议在settings.xml中明文存储GPG密码
- 考虑使用Maven的`settings-security.xml`文件来加密密码
- 或者使用GPG的密码缓存功能避免每次输入

## 5. 下一步
测试成功后，您就可以准备将项目发布到中央仓库了。完整的发布流程请参考项目中的`RELEASE_GUIDE.md`文件。