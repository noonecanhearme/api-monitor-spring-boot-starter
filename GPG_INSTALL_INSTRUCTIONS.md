# Windows系统GPG安装详细步骤

## 1. 下载Gpg4win安装程序
- 访问官方下载页面：https://www.gpg4win.org/download.html
- 点击下载最新版本的Gpg4win

## 2. 安装GPG
- 双击下载的安装程序
- 在安装向导中，选择完整安装选项（包括GPA - GNU Privacy Assistant）
- 按照默认设置完成安装

## 3. 配置环境变量
1. 右键点击"此电脑"或"我的电脑"，选择"属性"
2. 点击"高级系统设置" -> "环境变量"
3. 在"系统变量"中找到"Path"，点击"编辑"
4. 点击"新建"，添加Gpg4win的bin目录路径，通常是：
   `C:\Program Files (x86)\GnuPG\bin`
5. 点击"确定"保存所有更改

## 4. 验证安装
- 打开新的命令提示符（PowerShell或CMD）
- 运行命令：`gpg --version`
- 如果安装成功，将显示GPG的版本信息

## 5. 后续步骤
安装完成后，请回到项目目录，继续执行以下任务：
1. 生成GPG密钥对
2. 将公钥发布到密钥服务器
3. 配置settings.xml使用GPG
4. 测试GPG签名功能

请在完成安装后告知，我们将继续后续步骤。