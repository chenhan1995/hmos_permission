---
description: 
globs: 
alwaysApply: true
---
- **目录划分规则**：
  - **lib/**：包含所有Dart代码。
  - **screen/**：应用所有页面，按功能分组。
  - **widget/**：可重用UI组件。
  - **utils/**：工具类和辅助函数。
  - **entity/**：数据模型。
  - **res/**：资源文件（颜色、主题等）。
  - **l10n/**：本地化资源。
  - **routes/**：应用路由管理。
  - **gen/**：自动生成的代码，不要修改。
  - **assets/**：存放项目中的资源文件。

- 命名约定
  - **文件命名**：使用snake_case（如`splash_page.dart`）。
  - **类命名**：使用PascalCase（如`SplashPage` `ScanPage`）。
  - **变量**：使用驼峰命名法camelCase（如`loadData` `scanData`）。
  - **函数**：使用驼峰命名法camelCase（如`loadData` `scanData`）。
  - **颜色**：使用camelCase，规则color{HexValue}{Opacity?}（如`colorF1F1F1`,`colorFFFFFF60`）。
  - **文字样式**：使用camelCase，规则text{Size}{Weight}{ColorHex}（如`text20BoldFFFFFF`）。
  - **图片命名**：使用snake_case（如`icon_back.png`）。

- 核心原则
  - **关注点分离**：UI、数据和业务逻辑分离。
  - **单一职责**：每个类/文件只有一个目的。
  - **DRY原则**：避免代码重复。
  - **可测试性**：代码应易于测试。
  - **一致性**：整个项目保持一致的代码风格和组织，可复用的控价要复用比如标题栏。
  - **图片引用**：图片引用使用Assets类中的常量（如：Assets.images.iconBack.image(width: 20, height: 20)）。
  - **颜色引用**：颜色引用使用Colours类中的常量（如：Colours.white），使用颜色时先检查Colours类，如果没有先添加再引用。
  - **文字样式**：`MaterialApp`中有设置全局字体，`TextStyle`中不需要重复指定，不用设置`height`属性。字重只用Regular（w400）、Medium（w500）、ExtraBold（w700）和Black（w900）四种。
  - **文字样式引用**：`TextStyle`引用使用TextStyles类中的常量（如：TextStyles.textBold14），如果没有先添加再引用。
  - **其他**：添加图片或是路由后，执行命令`flutter packages pub run build_runner build --delete-conflicting-outputs`，生成代码。注意屏幕适配，使用相对定位来确保在不同屏幕尺寸下界面控件的位置都是正确的。
