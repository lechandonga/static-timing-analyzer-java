# static-timing-analyzer-java

基于 Spring Boot 的**纯本地静态时序分析**演示：用本地文本文件模拟单元库、网表和时序约束，
支持多时钟域、跨时钟域（CDC）归类、伪路径/多周期例外、组合环定位、未约束端点隔离，
以及可区分错误码的严格输入校验。不引入任何新三方库，也不依赖外部 STA 工具或服务。

## 快速开始

```bash
mvn test                       # 运行全部单测（日志按业务打印用例覆盖）
mvn -DskipTests package
java -jar target/static-timing-analyzer-java-1.0-SNAPSHOT.jar \
     --sta=samples/standard.lib,samples/multiclock.gate,samples/exceptions.sdc
```

也可以启动 Web 服务后调用 `POST /api/sta`（body 为三个本地文件路径）。

## 文档

输入文件格式、时序计算口径、例外优先级、错误码和验证方法见
[`docs/STA.md`](docs/STA.md)。
