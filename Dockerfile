# 阴阳师竞猜采集服务
# 用法: 将打好的 jar 包与本文件放在同一目录, 执行
#   docker build -t onmyoji-guess .
#   docker run -d --name onmyoji-guess --network host --restart unless-stopped onmyoji-guess
FROM eclipse-temurin:17-jre

WORKDIR /app

# jar 与 Dockerfile 放同一目录, 版本号变化无需改此文件
COPY *.jar app.jar

# SERVER_PORT: Spring Boot 自动映射为 server.port (host 网络模式, 无 -p 端口映射)
# TZ: 定时任务与日志按北京时间
ENV TZ=Asia/Shanghai \
    SERVER_PORT=5231

EXPOSE 5231

ENTRYPOINT ["java", "-jar", "app.jar"]
