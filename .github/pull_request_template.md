## 描述

改动内容与动机。

## 验证

- [ ] `gradle testDebugUnitTest`（或 `./gradlew :app:testDebugUnitTest`）通过
- [ ] `gradle assembleDebug` 通过
- [ ] 涉及算法 / 渲染 / 会话落盘的改动已补对应 instrumentation 用例

> 本仓库的测试按「变异验证」标准写：每条断言都要能因对应实现被改坏而失败，
> 否则不算覆盖。详见 README 第 1、6 节。

## 兼容性提醒

- AGP 4.1.3 / Gradle 7.6.4 / OpenCV 4.10.0 是钉住的组合，升级前先看 README 的说明
- 升级 OpenCV 必须在真机验证（Apple 芯片模拟器上的 SVE 假阳性绕不过去）
