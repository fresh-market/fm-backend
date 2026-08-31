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
 * 통합 테스트의 위치와 이름을 강제한다.
 *
 * integrationTest 태스크는 이름으로 거르지 않고 이 소스셋의 클래스를 전부 실행한다.
 * 이름 필터를 걸면 잘못 지은 테스트가 조용히 실행되지 않으므로 그렇게 하지 않는다.
 * 대신 어긋난 것을 여기서 실패로 알린다.
 *
 * 규칙이 src/test 가 아니라 이쪽에 있는 이유는, 단위 테스트 소스셋의 클래스패스에
 * 이 소스셋의 출력이 올라오지 않아 저쪽에서는 보이지 않기 때문이다.
 */
@AnalyzeClasses(packages = "com.freshmarket",
        importOptions = PlacementIntegrationTest.MainAndIntegrationTests.class)
class PlacementIntegrationTest {

    private static final String MAIN = "/classes/java/main/";
    private static final String OWN = "/classes/java/integrationTest/";
    private static final String BASE = "com.freshmarket";

    /*
     * main 출력이 이 소스셋의 클래스패스에 함께 올라온다.
     * 위치 대조에 그 둘이 다 필요하므로 함께 읽고 아래에서 갈라 쓴다.
     */
    static class MainAndIntegrationTests implements ImportOption {
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

    @ArchTest
    static void 이름이_IntegrationTest_로_끝난다(JavaClasses classes) {
        List<String> bad = own(classes).stream()
                .filter(c -> !c.getSimpleName().endsWith("IntegrationTest"))
                .map(JavaClass::getName)
                .collect(Collectors.toList());
        fail("통합 테스트 이름", bad, "파일명만 보고 단위인지 통합인지 알 수 있어야 한다. ~IntegrationTest 로 끝낸다");
    }

    /*
     * internal 밖에는 테스트를 두지 않는다.
     * 공개 창구(OrderApi)는 계약이라 동작이 없고, 그것이 지켜지는지는 구현의 테스트가 본다.
     */
    @ArchTest
    static void 테스트는_internal_아래에만_둔다(JavaClasses classes) {
        List<String> bad = own(classes).stream()
                .filter(c -> !underInternal(c))
                .map(c -> c.getName() + "  (패키지 " + c.getPackageName() + ")")
                .collect(Collectors.toList());
        fail("통합 테스트 위치", bad, "internal 밖에는 테스트를 두지 않는다. internal 아래로 옮긴다");
    }

    /*
     * 프로덕션 패키지 아래에 둔다.
     *
     * 단위 테스트와 달리 정확히 같은 패키지를 요구하지 않는다. 통합 테스트는 계층을 가로지르므로
     * 대상보다 위에 두는 것이 자연스럽다.
     *
     *   main  order/internal/service/OrderService.java
     *   통합  order/domain/OrderIntegrationTest.java
     *
     * 도메인 목록을 여기 적지 않고 main 에 실재하는 패키지와 대조한다.
     * 목록을 두면 domain-map.md, ArchitectureTest 에 이어 고칠 곳이 하나 더 는다.
     */
    @ArchTest
    static void 프로덕션_패키지_아래에_둔다(JavaClasses classes) {
        Set<String> mainPackages = classes.stream()
                .filter(c -> from(c, MAIN))
                .map(JavaClass::getPackageName)
                .collect(Collectors.toSet());
        List<String> bad = own(classes).stream()
                .filter(PlacementIntegrationTest::underInternal)
                .filter(c -> mainPackages.stream().noneMatch(m -> covers(c.getPackageName(), m)))
                .map(c -> c.getName() + "  (패키지 " + c.getPackageName() + " 아래에 프로덕션 클래스가 없다)")
                .collect(Collectors.toList());
        fail("통합 테스트 패키지", bad, "대상 도메인 패키지 아래에 둔다. 오타나 없는 도메인에 두면 아무도 찾지 못한다");
    }

    // 테스트 패키지가 프로덕션 패키지와 같거나 그 조상이면 통과다
    private static boolean covers(String testPkg, String mainPkg) {
        return mainPkg.equals(testPkg) || mainPkg.startsWith(testPkg + ".");
    }
}
