MDWechat
====
# 简介
Fork 自 [Blankeer/MDWechat](https://github.com/Blankeer/MDWechat)，当前维护分支为 `MD3v1.0`。

- 主要适配微信 `8.0.49`
- 支持的微信版本范围：`6.7.3 - 8.0.49`
- 只支持 Android 5.0 及以上

# 近期更新
1. 发布 `MD3v1.0`，主要围绕微信 `8.0.49` 调整 Material 3 风格。
2. 重做主界面底部 Tab、悬浮按钮、会话列表、通讯录、发现、我等页面视觉细节。
3. 重构聊天气泡渲染，支持连续消息智能分组、圆角联动、阴影层次、头像/昵称对齐、引用消息和自定义颜色。
4. 改善进入会话和滚动时的表现，减少原始气泡闪现、分组错乱和明显卡顿。
5. 构建环境升级到 `AGP 7.1.2 + Gradle 7.5 + Kotlin 1.6.21`，命令行构建需要 `Java 11+`。

# 效果预览
![gif_demo](image/demo.gif)
![main00](image/main00.png)
![chat00](image/chat00.png)
![main01](image/main01.png)
![main02](image/main02.png)
![main03](image/main03.png)
![main05](image/main05.png)
![chat01](image/chat01.png)

# 功能
实现的功能有:
1. 微信号隐藏(本机) (4.0新增)
2. 针对 微信8.0 提供 第四页取消沉浸背景显示 的功能 (4.0新增)
3. 细化设置项并添加4个内置配色方案(4.0新增)
4. 全局 ActionBar 和 状态栏 颜色修改,支持主界面和聊天页面的沉浸主题(4.0新增)
5. 自动识别微信深色模式以调整MDwechat配色方案(3.6新增)
6. 主界面 TabLayout Material 化,支持自定义图标
7. 主界面 4 个页面背景修改
8. 主界面添加悬浮按钮(FloatingActionButton),支持自定义按钮文本/图标/入口, 4.0支持自定义悬浮按钮点击之后的旋转角度
9. ~~主界面搜索 Material 化~~(2.0未加入)
10. 全局头像圆角
11. 全局状态栏颜色修改,支持半透明/全透明(沉浸)
12. 主界面列表去掉分割线,增加 Ripple 效果(按下水波纹),支持修改颜色
13. ~~主界面支持隐藏 发现/设置 页面~~(2.0未加入)
14. ~~支持聊天列表置顶底色修改~~(2.0未加入)
15. 聊天气泡修改，支持 MD3 风格智能分组、连续消息圆角联动、阴影层次、头像/昵称对齐、引用消息适配，并保留自定义颜色和文本颜色。
16. ~~发现页面支持隐藏朋友圈/扫一扫/摇一摇/附近的人/游戏/购物/小程序~~(微信自带,2.0已去掉)
17. ~~移除会话列表下拉小程序,最低支持微信 6.6.2~~(微信7.0.0以上失效)
18. 识别微X模块入口,移动到悬浮按钮(2.0新增)
19. 主界面字体颜色修改(2.0新增)

# 版本支持
- 支持的微信版本: 6.7.3 - 8.0.49；由于测试不够全面，MDWechat 的某些功能可能对于某些微信版本不生效。若不生效可以升级微信版本或者反馈问题到 Issue 里。
~~- MDWechat(官改) 4.0 对于国内版的适配性比较好，play版微信在部分机型/框架上可能出现无法适配的状况。~~(4.1已修复这一部分play版适配)

# 构建说明
1. 命令行构建请使用 `Java 11` 到 `Java 17`。
2. 调试构建命令：
   `./gradlew :app:assembleDebug`
3. 安装到设备：
   `./gradlew :app:installDebug`
4. `installDebug` 在安装完成后会自动重启目标微信进程。

# 存在的问题
1. 当前主要围绕微信 `8.0.49` 国内版实测，其他版本或 Play 版可能需要重新适配配置。
2. 聊天气泡依赖微信消息列表和消息行结构，微信更新后可能需要调整分组和引用消息识别。
3. 悬浮按钮在部分机型或框架组合上仍可能出现位置或显示异常。
4. 沉浸背景时，朋友圈顶栏图片在部分分辨率上可能显示错位。

# 感谢
1. [Blankeer/MDWechat](https://github.com/Blankeer/MDWechat)
2. [WechatSpellbook](https://github.com/Gh0u1L5/WechatSpellbook)
3. [WechatUI](https://www.coolapk.com/apk/ce.hesh.wechatUI)
4. [群消息助手](https://github.com/zhudongya123/WechatChatroomHelper)
5. [WechatMagician](https://github.com/Gh0u1L5/WechatMagician)
6. [ForceWechatDarkMode](https://github.com/chouqibao/ForceWechatDarkMode)

(背景图片来源于网络)


