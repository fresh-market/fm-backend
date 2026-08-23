package com.freshmarket;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import jakarta.persistence.Entity;
import java.util.Arrays;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/*
 * 패키지 경계를 빌드에서 강제한다 (DPB-6-03).
 * 규칙의 근거는 docs/code-architecture/domain-package-boundary-guideline.md 5장과 6장에 있다.
 *
 * 도메인이 생기기 전에 넣는다. 클래스가 쌓인 뒤에 넣으면 이미 깨진 것을 무더기로 만나
 * 고치는 대신 규칙을 끄게 된다.
 */
@AnalyzeClasses(packages = "com.freshmarket", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String BASE = "com.freshmarket";

    /*
     * 검사 대상이 0개인 규칙은 ArchUnit 이 기본으로 실패시킨다.
     * 도메인이 아직 없어 대부분의 규칙이 그 상태이므로 빈 것을 허용한다.
     * 규칙이 잠자고 있다가 첫 도메인이 생기는 순간부터 문다.
     */

    /*
     * 다른 도메인의 domain 패키지에 손대지 못하게 막는다.
     * 도메인끼리는 루트에 공개한 Api 인터페이스와 DTO 로만 오간다.
     */
    @ArchTest
    static final ArchRule 도메인_내부는_다른_도메인에_닫혀_있다 = slices()
            .matching(BASE + ".(*)..")
            .namingSlices("$1")
            .should().notDependOnEachOther()
            .ignoreDependency(
                    resideInAPackage(BASE + ".."),
                    resideInAnyPackage(
                            BASE + ".*",            // 도메인 루트만 허용
                            BASE + ".common..",
                            BASE + ".config.."));

    /*
     * 도메인 안에서 위에서 아래로만 흐르게 한다.
     * consideringOnlyDependenciesInLayers 를 주지 않으면 계층 밖 클래스까지 검사해 오탐이 난다.
     */
    @ArchTest
    static final ArchRule 계층은_아래로만_흐른다 = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .withOptionalLayers(true)
            .layer("Controller").definedBy("..domain.controller..")
            .layer("Service").definedBy("..domain.service..")
            .layer("Repository").definedBy("..domain.repository..")
            .layer("Client").definedBy("..domain.client..")
            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service")
            .whereLayer("Client").mayOnlyBeAccessedByLayers("Service");

    /*
     * 도메인 사이의 호출을 층으로 강제한다 (DPB-5-01, DPB-5-02).
     *
     * 층별로 묶지 않고 도메인 하나를 층 하나로 선언한다.
     * layeredArchitecture 는 같은 층 안의 호출을 허용하므로, L1 셋을 한 층으로 묶으면
     * cart 가 coupon 을 직접 부르는 것을 못 잡는다. 실제로 확인했다.
     *
     * 층 배정은 docs/code-architecture/domain-map.md 에 있다. 도메인을 더하면 여기도 고친다.
     */
    private static final String[] L1 = {"stock", "coupon", "cart"};
    private static final String[] L2 = {"order", "payment", "claim", "shipment",
                                        "review", "qna", "statistics"};

    @ArchTest
    static final ArchRule 도메인은_아래로만_부른다 = domainLayers();

    private static ArchRule domainLayers() {
        var arch = layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .withOptionalLayers(true);
        for (String d : concat(new String[]{"member", "product", "admin"}, L1, L2)) {
            arch = arch.layer(d).definedBy(BASE + "." + d + "..");
        }
        // L0 는 L1 과 L2 전부가 부를 수 있다
        for (String d : new String[]{"member", "product", "admin"}) {
            arch = arch.whereLayer(d).mayOnlyBeAccessedByLayers(concat(L1, L2));
        }
        /*
         * L1 은 L2 만 부를 수 있다.
         * 같은 층인 L1 끼리는 목록에 없으므로 막힌다.
         */
        for (String d : L1) {
            arch = arch.whereLayer(d).mayOnlyBeAccessedByLayers(L2);
        }
        // L2 는 아무도 부르지 못한다
        for (String d : L2) {
            arch = arch.whereLayer(d).mayNotBeAccessedByAnyLayer();
        }
        return arch;
    }

    private static String[] concat(String[]... parts) {
        return java.util.Arrays.stream(parts).flatMap(java.util.Arrays::stream)
                .toArray(String[]::new);
    }

    /*
     * 계층 패키지의 클래스는 그 계층 이름을 접미사로 갖는다 (DPB-4-10).
     *
     * 커버리지 게이트가 service 패키지 전체를 100% 로 요구한다 (BLD-1-01).
     * 그 패키지에 정책 객체나 계산 헬퍼를 두면 그것들에도 100% 가 요구된다.
     * 이름을 강제하면 "여기 있는 것은 전부 서비스다" 가 성립한다.
     *
     * 접미사를 붙일 수 없는 클래스는 그 계층에 속하지 않는다. 정책 객체나 계산 헬퍼는
     * service 패키지가 아니라 domain 바로 아래나 도메인 루트에 둔다.
     */
    @ArchTest
    static final ArchRule 컨트롤러_이름 = layerSuffix("controller", "Controller");

    @ArchTest
    static final ArchRule 서비스_이름 = layerSuffix("service", "Service");

    @ArchTest
    static final ArchRule 레포지토리_이름 = layerSuffix("repository", "Repository");

    private static ArchRule layerSuffix(String layer, String suffix) {
        return classes()
                .that().resideInAPackage("..domain." + layer + "..")
                .and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith(suffix)
                .allowEmptyShould(true);
    }

    // 순환 의존은 어느 층에서든 막는다
    @ArchTest
    static final ArchRule 순환_의존이_없다 = slices()
            .matching(BASE + ".(*)..")
            .should().beFreeOfCycles();

    /*
     * API 구현체와 외부 연동 클래스에 트랜잭션을 걸지 않는다 (DPB-3-04, DPB-7-01).
     * 호출당하는 쪽이 트랜잭션을 열면 호출하는 도메인의 트랜잭션 경계가 흐려지고,
     * 외부 호출이 트랜잭션 안에 들어가면 그동안 커넥션과 락을 점유한다.
     */
    @ArchTest
    static final ArchRule ApiImpl_에_트랜잭션이_없다 = noClasses()
            .that().haveSimpleNameEndingWith("ApiImpl")
            .should().beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule ApiImpl_메서드에_트랜잭션이_없다 = noMethods()
            .that().areDeclaredInClassesThat().haveSimpleNameEndingWith("ApiImpl")
            .should().beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule client_에_트랜잭션이_없다 = noClasses()
            .that().resideInAPackage("..domain.client..")
            .should().beAnnotatedWith(Transactional.class)
            .allowEmptyShould(true);

    /*
     * 엔티티는 도메인 안에만 둔다 (DPB-2-01).
     * 루트에 올라오면 다른 도메인이 엔티티를 직접 받게 되어 내부 구조가 계약이 된다.
     */
    @ArchTest
    static final ArchRule 엔티티는_도메인_안에만_있다 = classes()
            .that().areAnnotatedWith(Entity.class)
            .should().resideInAPackage("..domain.entity..")
            .allowEmptyShould(true);

    /*
     * common 은 도메인을 모른다 (DPB-5-03).
     * 여기서 개별 도메인 예외를 잡거나 도메인 타입을 참조하면 경계가 무너진다.
     */
    @ArchTest
    static final ArchRule common_은_도메인을_모른다 = noClasses()
            .that().resideInAnyPackage(BASE + ".common..", BASE + ".config..")
            .should().dependOnClassesThat()
            .resideInAPackage(BASE + ".*.domain..")
            .allowEmptyShould(true);

    private static final DescribedPredicate<JavaClass> 스케줄_메서드를_가진다 =
            new DescribedPredicate<>("@Scheduled 메서드를 가진다") {
                @Override
                public boolean test(JavaClass c) {
                    return c.getMethods().stream().anyMatch(m -> m.isAnnotatedWith(Scheduled.class));
                }
            };

    private static final ArchCondition<JavaClass> batch_프로필로_묶인다 =
            new ArchCondition<>("@Profile(\"batch\") 로 묶인다") {
                @Override
                public void check(JavaClass c, ConditionEvents events) {
                    boolean 묶였다 = c.isAnnotatedWith(Profile.class)
                            && Arrays.asList(c.getAnnotationOfType(Profile.class).value()).contains("batch");
                    if (!묶였다) {
                        events.add(SimpleConditionEvent.violated(c,
                                c.getName() + " 에 @Profile(\"batch\") 가 없다"));
                    }
                }
            };

    /*
     * 스케줄러는 batch 프로필에서만 뜬다 (INF-1-10).
     *
     * 앱 ASG 는 최대 2대까지 뜨고 배치 전용 인스턴스만 prod,batch 로 뜬다.
     * 분산 락이 없어 프로필이 유일한 방어선이고, 이것이 빠지면 같은 행을 여러 대가 동시에 집는다.
     * SchedulingConfig 가 앱 프로필에서 @EnableScheduling 을 꺼 두는 것에 기대지 않는다.
     * 그 설정이 나중에 바뀌면 @Profile 을 안 단 스케줄러가 한꺼번에 깨어나기 때문이다.
     */
    @ArchTest
    static final ArchRule 스케줄러는_batch_프로필에만_있다 = classes()
            .that(스케줄_메서드를_가진다)
            .should(batch_프로필로_묶인다)
            .allowEmptyShould(true);
}
