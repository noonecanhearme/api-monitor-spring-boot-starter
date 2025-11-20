# GPG签名配置完整指南

本文档详细说明如何安装、配置和使用GPG签名工具，用于Maven中央仓库发布。

## 1. 安装GPG

### Windows系统
1. 下载Gpg4win安装程序：https://www.gpg4win.org/download.html
2. 运行安装程序，选择完整安装（包括GPA - GNU Privacy Assistant）
3. 安装完成后，将Gpg4win的bin目录（通常是`C:\Program Files (x86)\GnuPG\bin`）添加到系统环境变量PATH中
4. 验证安装：打开命令提示符，运行 `gpg --version`

### macOS系统
```bash
# 使用Homebrew安装
brew install gnupg

# 验证安装
gpg --version
```

### Linux系统（Debian/Ubuntu）
```bash
sudo apt-get update
sudo apt-get install gnupg

# 验证安装
gpg --version
```

## 2. 生成GPG密钥对

打开命令行工具，执行以下命令生成新的密钥对：

```bash
gpg --full-generate-key
```

按照提示进行配置：

1. **选择密钥类型**：输入 `1` 选择RSA and RSA (默认)
2. **密钥长度**：输入 `4096` (推荐的安全长度)
3. **过期时间**：
   - 输入 `365` 设置一年后过期
   - 或输入 `0` 设置永不过期
4. **确认设置**：输入 `y`
5. **输入用户信息**：
   - 真实姓名：输入您的姓名（如 "Your Name"）
   - 电子邮件地址：输入与Sonatype账户关联的邮箱
   - 注释（可选）：可以留空
6. **确认用户信息**：输入 `O` (确定)
7. **设置密码短语**：输入一个强密码并记住它（发布时需要使用）

密钥生成过程可能需要一些时间（生成随机数据）。

## 3. 列出和导出密钥

### 列出您的密钥

```bash
gpg --list-secret-keys --keyid-format LONG
```

输出示例：
```
sec   rsa4096/ABCDEF1234567890 2023-01-01 [SC] [expires: 2024-01-01]
      XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX
uid                 [ultimate] Your Name <your.email@example.com>
ssb   rsa4096/XYZ1234567890ABC 2023-01-01 [E]
```

**重要**：复制您的密钥ID（上例中的`ABCDEF1234567890`）

### 导出公钥

```bash
gpg --armor --export ABCDEF1234567890 > public_key.asc
```

### 将公钥发布到密钥服务器

必须将公钥发布到密钥服务器，以便Sonatype验证：

```bash
gpg --send-keys --keyserver keyserver.ubuntu.com ABCDEF1234567890
```

您也可以使用其他密钥服务器：
```bash
# 使用pgp.mit.edu
pgp --send-keys --keyserver pgp.mit.edu ABCDEF1234567890

# 使用keys.openpgp.org
pgp --send-keys --keyserver keys.openpgp.org ABCDEF1234567890
```

## 4. 配置Maven使用GPG

### 方法1：在settings.xml中配置（推荐）

编辑您的`~/.m2/settings.xml`文件，添加以下配置：

```xml
<settings>
  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <gpg.executable>gpg</gpg.executable>
        <gpg.passphrase>您的GPG密码</gpg.passphrase>
        <gpg.keyname>ABCDEF1234567890</gpg.keyname>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>gpg</activeProfile>
  </activeProfiles>
</settings>
```

### 方法2：在pom.xml中配置

如果不想在settings.xml中存储密码，可以在项目的pom.xml中添加以下配置：

```xml
<build>
  <plugins>
    <plugin>
      <groupId>org.apache.maven.plugins</groupId>
      <artifactId>maven-gpg-plugin</artifactId>
      <version>3.0.1</version>1</version>
      <executions>
        <execution>
          <id>sign-artifacts</id>
          <phase>verify</phase>
          <goals>
            <goal>sign</goal>
          </goals>
          <configuration>
            <gpgArguments>
              <arg>--pinentry-mode</arg>
              <arg>loopback</arg>
            </gpgArguments>
          </configuration>
        </execution>
      </executions>
    </plugin>
  </plugins>
</build>
```

## 5. 测试GPG签名

在项目根目录执行以下命令测试签名：

```bash
mvn clean verify -Prelease
```

如果配置正确，Maven将使用GPG对构建的工件进行签名，并显示类似以下输出：

```
[INFO] --- maven-gpg-plugin:3.0.1:sign (sign-artifacts) @ api-monitor-spring-boot-starter ---
[INFO] Signing file api-monitor-spring-boot-starter-1.0.0.jar with key ABCDEF1234567890
[INFO] Signing file api-monitor-spring-boot-starter-1.0.0-sources.jar with key ABCDEF1234567890
[INFO] Signing file api-monitor-spring-boot-starter-1.0.0-javadoc.jar with key ABCDEF1234567890
```

## 6. 常见问题排查

### 找不到gpg命令
- 确保GPG已正确安装
- 检查环境变量PATH是否包含GPG的bin目录
- 在Windows上，可以尝试使用完整路径：`C:\Program Files (x86)\GnuPG\bin\gpg.exe`

### 密钥未找到错误
- 检查`gpg.keyname`是否与生成的密钥ID完全匹配
- 确保使用的是长格式密钥ID

### 密码错误
- 确保输入的GPG密码正确
- 检查密码中是否有特殊字符需要转义

### 密钥服务器同步问题
- 密钥服务器同步可能需要几分钟到几小时
- 可以使用不同的密钥服务器
- 验证公钥是否已发布：
  ```bash
  gpg --keyserver keyserver.ubuntu.com --search-keys your.email@example.com
  ```

## 7. 安全性注意事项

1. **妥善保管私钥**：私钥是您身份的证明，请勿共享
2. **记住密码短语**：如果忘记，将无法使用密钥
3. **定期备份密钥**：
   ```bash
   # 导出私钥（请安全存储）
   gpg --export-secret-keys --armor ABCDEF1234567890 > private_key_backup.asc
   ```
4. **考虑设置密钥过期时间**：定期更新密钥更安全

## 8. 额外资源

- [GnuPG官方文档](https://www.gnupg.org/documentation/)
- [Sonatype Central GPG签名指南](https://central.sonatype.com/publishing/gpg-signatures)
- [Maven GPG Plugin文档](https://maven.apache.org/plugins/maven-gpg-plugin/)

祝配置顺利！