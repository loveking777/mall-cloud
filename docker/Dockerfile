# ============================================================
# 通用应用镜像
#
# 通过构建参数 JAR_FILE 指定要打进镜像的 jar，
# 四个服务共用这一个 Dockerfile，避免复制四份。
#
# 注意：这里假设 jar 已经在宿主机上打包好（mvn -DskipTests package），
# 容器里不再执行 Maven 构建，构建速度快很多。
# ============================================================

FROM eclipse-temurin:17-jre

ARG JAR_FILE

ENV TZ=Asia/Shanghai \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -Duser.timezone=Asia/Shanghai"

WORKDIR /app

COPY ${JAR_FILE} app.jar

EXPOSE 8080

# 用 sh -c 是为了让 JAVA_OPTS 里的参数生效
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
