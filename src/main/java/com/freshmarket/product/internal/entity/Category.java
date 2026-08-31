package com.freshmarket.product.internal.entity;

import com.freshmarket.common.entity.BaseMutableTimeEntity;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 상품 카테고리. parent_id로 자기 자신을 참조해 계층(상위/하위) 구조를 표현한다
@Entity
@Table(name = "category")
@AttributeOverride(name = "id", column = @Column(name = "category_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category extends BaseMutableTimeEntity {

    private static final int NAME_MAX_LENGTH = 50;

    // 상위 카테고리 ID. 최상위 카테고리면 null
    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "name", nullable = false, length = NAME_MAX_LENGTH)
    private String name;

    private Category(String name, Long parentId) {
        validateName(name);
        this.name = name;
        this.parentId = parentId;
    }

    // 최상위 카테고리를 만든다
    public static Category register(String name) {
        return new Category(name, null);
    }

    // 지정한 부모 아래에 하위 카테고리를 만든다
    public static Category register(String name, Long parentId) {
        return new Category(name, parentId);
    }

    // 이름을 바꾼다. parentId는 이 메서드로 바꿀 수 없다
    public void rename(String newName) {
        validateName(newName);
        this.name = newName;
    }

    // 이름이 비어있지 않고 길이 제한을 넘지 않는지 검사한다
    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name 은 필수다");
        }
        if (name.length() > NAME_MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "name 은 " + NAME_MAX_LENGTH + "자를 넘을 수 없다: " + name.length());
        }
    }
}