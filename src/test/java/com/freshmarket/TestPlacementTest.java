package com.freshmarket;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.core.importer.Location;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/*
 * 단위 테스트의 위치를 강제한다.
 *
 * 통합 테스트가 여기 섞이면 단위 테스트로 실행되어 그 기록이 test.exec 에 남고
 * 커버리지에 합산된다. 통합 테스트를 커버리지에서 빼기로 한 정책이 그대로 뚫린다 (BLD-1-04).
 *
 * Testcontainers 는 integrationTestImplementation 으로만 선언해 두어 여기서는
 * 애초에 컴파일되지 않는다. 그래도 규칙으로 남기는 이유는 누군가 의존성을
 * testImplementation 으로 옮기면 그 방어가 조용히 사라지기 때문이다.
 */
@AnalyzeClasses(packages = "com.freshmarket", importOptions = TestPlacementTest.MainAndUnitTests.class)
class TestPlacementTest {

    private static final String MAIN = "/classes/java/main/";
    private static final String OWN = "/classes/java/test/";
    private static final String BASE = "com.freshmarket";

    static class MainAndUnitTests implements ImportOption {
        @Override
        public boolean includes(Location location) {
            return location.contains(MAIN) || location.contains(OWN);
        }
    }

    private static boolean from(JavaClass c, String dir) {
        return c.getSource().map(s -> s.getUri().toString().contains(dir)).orElse(false);
    }

    /*
     * 베이스 패키지는 아키텍처 테스트 자리다.
     * 도메인에 속하지 않으므로 아래 규칙에서 뺀다.
     */
    private static List<JavaClass> own(JavaClasses classes) {
        return classes.stream()
                .filter(c -> from(c, OWN))
                .filter(JavaClass::isTopLevelClass)
                .filter(c -> !c.getPackageName().equals(BASE))
                .collect(Collectors.toList());
    }

    private static boolean underInternal(JavaClass c) {
        String p = c.getPackageName();
        return p.contains(".internal.") || p.endsWith(".internal");
    }

    private static void fail(String rule, List<String> bad, String how) {
        if (!bad.isEmpty()) {
            throw new AssertionError(rule + " 위반 " + bad.size() + "건\n  "
                    + String.join("\n  ", bad) + "\n" + how);
        }
    }

    /*
     * internal 밖에는 테스트를 두지 않는다.
     * 공개 창구(OrderApi)와 그 DTO 는 도메인 사이의 계약이다. 계약 자체에는 동작이 없고,
     * 그것이 지켜지는지는 구현(OrderApiImpl)의 테스트가 본다.
     */
    @ArchTest
    static void 테스트는_internal_아래에만_둔다(JavaClasses classes) {
        List<String> bad = own(classes).stream()
                .filter(c -> !underInternal(c))
                .map(c -> c.getName() + "  (패키지 " + c.getPackageName() + ")")
                .collect(Collectors.toList());
        fail("단위 테스트 위치", bad, "internal 밖에는 테스트를 두지 않는다. 구현이 있는 internal 아래로 옮긴다");
    }

    /*
     * 대상과 정확히 같은 패키지에 둔다.
     * ~ApiImpl 과 Controller 가 package-private 이라(DPB-6-01) 패키지가 어긋나면 닿지 못한다.
     */
    @ArchTest
    static void 프로덕션_패키지를_미러링한다(JavaClasses classes) {
        Set<String> mainPackages = classes.stream()
                .filter(c -> from(c, MAIN))
                .map(JavaClass::getPackageName)
                .collect(Collectors.toSet());
        List<String> bad = own(classes).stream()
                .filter(TestPlacementTest::underInternal)
                .filter(c -> !mainPackages.contains(c.getPackageName()))
                .map(c -> c.getName() + "  (패키지 " + c.getPackageName() + " 에 프로덕션 클래스가 없다)")
                .collect(Collectors.toList());
        fail("단위 테스트 패키지", bad, "대상 클래스와 같은 패키지에 둔다 (DPB-6-01)");
    }

    @ArchTest
    static void 스프링_컨텍스트를_띄우지_않는다(JavaClasses classes) {
        List<String> bad = own(classes).stream()
                .filter(c -> c.isAnnotatedWith("org.springframework.boot.test.context.SpringBootTest"))
                .map(JavaClass::getName)
                .collect(Collectors.toList());
        fail("단위 테스트 위치", bad, "컨텍스트를 띄우는 테스트는 통합 테스트다. src/integrationTest 로 옮긴다");
    }

    @ArchTest
    static void 테스트컨테이너를_쓰지_않는다(JavaClasses classes) {
        List<String> bad = own(classes).stream()
                .filter(c -> c.getDirectDependenciesFromSelf().stream()
                        .anyMatch(d -> d.getTargetClass().getPackageName().startsWith("org.testcontainers")))
                .map(JavaClass::getName)
                .collect(Collectors.toList());
        fail("단위 테스트 위치", bad, "실제 DB 를 띄우는 테스트는 통합 테스트다. src/integrationTest 로 옮긴다");
    }
}
