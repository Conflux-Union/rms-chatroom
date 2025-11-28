#!/bin/bash
# ========================================
# Android SDK 命令行安装脚本 (国内镜像)
# 运行方式: chmod +x setup-sdk.sh && ./setup-sdk.sh
# ========================================

set -e

echo ">>> 1. 创建目录"
mkdir -p ~/Android/Sdk/cmdline-tools
cd ~/Android/Sdk/cmdline-tools

echo ">>> 2. 下载命令行工具 (腾讯镜像)"
if [ ! -f "cmdline-tools.zip" ]; then
    wget https://mirrors.cloud.tencent.com/AndroidSDK/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
fi

echo ">>> 3. 解压并整理目录"
unzip -o cmdline-tools.zip
rm -rf latest 2>/dev/null || true
mv cmdline-tools latest
rm cmdline-tools.zip

echo ">>> 4. 添加环境变量到 ~/.bashrc"
if ! grep -q "ANDROID_HOME" ~/.bashrc; then
    cat >> ~/.bashrc << 'ENVEOF'

# Android SDK
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0
ENVEOF
    echo "环境变量已添加"
else
    echo "环境变量已存在，跳过"
fi

echo ">>> 5. 立即生效"
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools

echo ">>> 6. 安装SDK组件"
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
    "platform-tools" \
    "platforms;android-35" \
    "build-tools;35.0.0"

echo ">>> 7. 接受许可证"
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses

echo ""
echo "========================================="
echo "安装完成！"
echo "请运行以下命令使环境变量生效："
echo "  source ~/.bashrc"
echo ""
echo "然后可以编译Android项目："
echo "  cd android && ./gradlew assembleDebug"
echo "========================================="
