# GPG密钥对生成详细步骤

## 1. 打开命令行工具
- 打开新的命令提示符（PowerShell或CMD）
- 确保已正确安装GPG并配置了环境变量

## 2. 生成GPG密钥对
运行以下命令开始生成密钥对：

```bash
gpg --full-generate-key
```

按照提示进行配置：

### 2.1 选择密钥类型
- 输入 `1` 选择RSA and RSA (默认)

### 2.2 设置密钥长度
- 输入 `4096` (推荐的安全长度)

### 2.3 设置过期时间
- 输入 `365` 设置一年后过期
- 或输入 `0` 设置永不过期

### 2.4 确认设置
- 输入 `y` 确认

### 2.5 输入用户信息
- **真实姓名**：输入您的姓名（如 "Your Name"）
- **电子邮件地址**：输入与Sonatype账户关联的邮箱
- **注释（可选）**：可以留空

### 2.6 确认用户信息
- 输入 `O` (确定)

### 2.7 设置密码短语
- 输入一个强密码并记住它（发布时需要使用）

## 3. 记录密钥ID
密钥生成完成后，运行以下命令查看您的密钥：

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

**请记下您的密钥ID**（上例中的`ABCDEF1234567890`），后续配置会用到。

## 4. 后续步骤
生成密钥对后，请继续执行以下任务：
1. 将公钥发布到密钥服务器
2. 配置settings.xml使用GPG
3. 测试GPG签名功能

请在完成密钥生成后告知，我们将继续后续步骤。