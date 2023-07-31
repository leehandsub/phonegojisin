FROM amazoncorretto:11-alpine-jdk # JDK 11 이미지를 불러옴
MAINTAINER fantdocker <tjddnr0110@naver.com>

EXPOSE 8080

ARG JAR_FILE=build/libs/*.jar # 빌드된 Jar 파일
COPY ${JAR_FILE} deploy-app.jar # deploy-app.jar 파일명으로 복사
CMD java -jar ./deploy-app.jar # jar 파일 실행