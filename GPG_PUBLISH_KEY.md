# 将GPG公钥发布到密钥服务器

## 1. 导出公钥
在命令行中运行以下命令，将您的公钥导出为ASCII格式：

```bash
# 请将ABCDEF1234567890替换为您的实际密钥ID
gpg --armor --export ABCDEF1234567890 > public_key.asc
```

## 2. 将公钥发布到密钥服务器

### 2.1 使用Ubuntu密钥服务器（推荐）
```bash
# 请将ABCDEF1234567890替换为您的实际密钥ID
gpg --send-keys --keyserver keyserver.ubuntu.com ABCDEF1234567890
```

### 2.2 使用其他密钥服务器（可选）
如果Ubuntu密钥服务器不可用，可以尝试其他密钥服务器：

使用MIT密钥服务器：
```bash
gpg --send-keys --keyserver pgp.mit.edu ABCDEF1234567890
```

使用OpenPGP密钥服务器：
```bash
gpg --send-keys --keyserver keys.openpgp.org ABCDEF1234567890
```

## 3. 验证公钥是否已成功发布

等待几分钟后（密钥服务器同步需要时间），可以通过以下命令验证您的公钥是否已成功发布：

```bash
# 使用您的邮箱地址搜索
gpg --keyserver keyserver.ubuntu.com --search-keys your.email@example.com

# 或者使用密钥ID搜索
gpg --keyserver keyserver.ubuntu.com --recv-keys ABCDEF1234567890
```

## 4. 注意事项
- 密钥服务器同步可能需要几分钟到几小时
- 如果一个密钥服务器不可用，尝试使用另一个
- 确保公钥成功发布是Sonatype中央仓库发布的必要条件

## 5. 后续步骤
公钥发布成功后，请继续执行以下任务：
1. 配置settings.xml使用GPG
2. 测试GPG签名功能

请在完成公钥发布后告知，我们将继续后续步骤。