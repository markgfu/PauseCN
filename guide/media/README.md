# 首页演示素材

这两段 GIF 是简化交互流程示意，不是实机录屏或自动化测试证据。所有背景、记忆、数字、应用选择与 AI 文字均为虚构示例，不来自手机记录，也没有为制作素材调用 AI API。

- `ai-reminder.gif`：背景与偏好、按授权预生成、下次停顿。
- `ai-report.gif`：本地热力图、AI 解读、分享已有解读。
- 同名 PNG：不播放动画的静态示意。

颜色采用应用的 Paper / Ink / Sage 配色。画面中的布局为介绍用途简化，实际控件与文案以 APK 为准。AI 示例不代表真实模型必定产生相同内容。

使用 Pillow 和本机合法可用的中文字体重新生成：

```sh
python -m pip install Pillow
python scripts/generate-readme-demos.py --font /path/to/cjk-font.ttf
```

Windows 默认使用系统的微软雅黑字体，不在仓库内分发字体。生成器只读取指定字体，不读取设备、密钥、私人记录，也不访问网络。Pillow 只是制作介绍素材的工具，不是 Android 构建依赖。
