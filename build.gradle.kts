import org.gradle.api.tasks.PathSensitivity
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.dwje"
version = "1.0.0"
description = "덕우전자 AX — 이상 알림 발송 엔진"

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}

repositories {
    mavenCentral()
}

dependencies {
    // Spring Boot — 배치 전용이므로 web 스타터를 넣지 않는다 (기동 시간·메모리 절감)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // 알림 메일 발송. LOG 모드에서는 쓰지 않지만 스타터가 있어야 SMTP 모드로 전환할 수 있다
    implementation("org.springframework.boot:spring-boot-starter-mail")

    // 대상 PostgreSQL — ax 스키마(조건·상태·알림·대기열) + mes 스키마(원천)
    runtimeOnly("org.postgresql:postgresql:42.7.5")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()

    // 프로파일은 실행하는 쪽이 밝힌다 (application.yml 에 기본값을 두지 않는다).
    systemProperty("spring.profiles.active", "test")

    // 테스트가 소스·리소스 밖에서 읽는 파일. 입력으로 선언하지 않으면 그 파일만 고쳐도
    // test 가 UP-TO-DATE 로 건너뛰어, 테스트는 있는데 검사가 돌지 않는다.
    inputs.files("config/engine.env.example", ".gitignore")
        .withPropertyName("envTemplates")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()
}

// ./gradlew bootRun 은 로컬 개발용이므로 local 프로파일로 띄운다.
tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    args("--spring.profiles.active=local")
}

// =====================================================================================
//  배포 묶음 — JAR 옆에 설정과 실행 스크립트를 함께 둔다 (이관 엔진과 같은 방식)
//
//  엔진은 `spring.config.import: optional:file:./config/local.yml` 로 **실행 위치 기준**
//  config/local.yml 을 읽는다. build/libs 에서 띄우면 build/libs/config/local.yml 이 잡힌다.
//
//  일반 빌드(bootJar/build)에는 붙이지 않는다. 매번 컴파일할 때마다 접속 정보가
//  빌드 산출물로 흘러나가는 것을 피하려는 것이다. 배포할 때만 `dist` 를 부른다.
// =====================================================================================
val bundleConfig by tasks.registering(Copy::class) {
    group = "distribution"
    description = "config/ 의 접속 설정을 build/libs/config 로 복사한다 (배포용, 접속 정보 포함)"

    from("config") { include("local.yml", "engine.env") }
    into(layout.buildDirectory.dir("libs/config"))

    // 원본과 같은 보호 수준을 유지한다. 기본 복사는 644 로 떨어져 같은 머신의
    // 다른 계정이 읽을 수 있게 된다.
    filePermissions { unix("600") }

    doFirst {
        listOf("local.yml", "engine.env")
            .filterNot { file("config/$it").exists() }
            .forEach { logger.warn("config/$it 가 없어 건너뜁니다. config/$it.example 을 복사해 값을 채우십시오.") }
    }

    doLast {
        val copied = layout.buildDirectory.dir("libs/config").get().asFile
            .listFiles()?.map { it.name }?.sorted().orEmpty()
        if (copied.isEmpty()) {
            logger.warn("복사된 설정이 없습니다 — build/libs 에서 실행하면 접속 설정이 없다고 거부됩니다.")
        } else {
            logger.lifecycle("배포 묶음 — build/libs/config 에 ${copied.joinToString(", ")} (600)")
            logger.lifecycle("  ⚠ 접속 정보가 빌드 산출물에 있습니다. build/libs 를 그대로 공유하지 마십시오.")
        }
    }
}

// start.sh / stop.sh / status.sh 는 JAR 과 같은 폴더에 있어야 한다. 세 스크립트 모두
// 자기 위치를 기준으로 JAR·설정·로그 경로를 잡기 때문이다.
//
// Copy 태스크로 만들지 않는다. Copy 는 `into` 로 준 디렉터리를 자기 출력으로 선언하고,
// Gradle 이 그 안에서 태스크가 만들지 않은 것을 낡은 산출물로 보고 지운다.
// build/libs 를 통째로 주면 엔진이 그 자리에서 돌며 쓰던 logs/ · run/ 이 빌드 한 번에 사라진다.
val DEPLOY_SCRIPTS = listOf("start.sh", "stop.sh", "status.sh")

val bundleScripts by tasks.registering {
    group = "distribution"
    description = "start.sh / stop.sh / status.sh 를 build/libs 로 복사한다 (배포용)"

    inputs.files(DEPLOY_SCRIPTS.map { file(it) })
        .withPropertyName("deployScripts")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.files(DEPLOY_SCRIPTS.map { layout.buildDirectory.file("libs/$it") })
        .withPropertyName("deployScriptsCopied")

    doLast {
        copy {
            from(layout.projectDirectory) { include(DEPLOY_SCRIPTS) }
            into(layout.buildDirectory.dir("libs"))
            filePermissions { unix("750") }
        }
        logger.lifecycle("배포 묶음 — build/libs 에 ${DEPLOY_SCRIPTS.joinToString(", ")} (750)")
    }
}

/** 배포용 빌드 — JAR + 설정 + 실행 스크립트 */
val dist by tasks.registering {
    group = "distribution"
    description = "배포용 빌드 — bootJar 후 접속 설정과 실행 스크립트를 JAR 옆에 둔다"
    dependsOn(tasks.named("bootJar"), bundleConfig, bundleScripts)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("alert-engine.jar")
}
