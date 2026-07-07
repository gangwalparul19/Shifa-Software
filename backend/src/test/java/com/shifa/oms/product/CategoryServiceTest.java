package com.shifa.oms.product;

import com.shifa.oms.common.DuplicateResourceException;
import com.shifa.oms.common.ResourceNotFoundException;
import com.shifa.oms.product.dto.CategoryRequest;
import com.shifa.oms.product.dto.CategoryResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link CategoryService} (Catalog & Discovery): the public list
 * returns active categories in order, slug derivation, duplicate rejection, and
 * soft-deactivation. Repository is mocked so these run without a database.
 */
@ExtendWith(MockitoExtension.class)
class CategoryServiceTest {

    @Mock
    private CategoryRepository categoryRepository;

    @InjectMocks
    private CategoryService categoryService;

    @Test
    void publicListReturnsActiveCategoriesInRepositoryOrder() {
        Category immunity = new Category("Immunity", "immunity", "d", 10, true);
        Category juices = new Category("Juices", "juices", "d", 40, true);
        when(categoryRepository.findByActiveTrueOrderBySortOrderAscNameAsc())
                .thenReturn(List.of(immunity, juices));

        List<CategoryResponse> result = categoryService.publicList();

        assertThat(result).extracting(CategoryResponse::slug)
                .containsExactly("immunity", "juices");
        assertThat(result).allMatch(CategoryResponse::active);
    }

    @Test
    void createDerivesSlugFromNameWhenBlank() {
        when(categoryRepository.existsByName(any())).thenReturn(false);
        when(categoryRepository.existsBySlug(any())).thenReturn(false);
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        CategoryResponse result = categoryService.create(
                new CategoryRequest("Hair & Skin", null, "care", 30, true));

        assertThat(result.slug()).isEqualTo("hair-and-skin");
        assertThat(result.name()).isEqualTo("Hair & Skin");
    }

    @Test
    void createRejectsDuplicateName() {
        when(categoryRepository.existsByName("Immunity")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(
                new CategoryRequest("Immunity", "immunity", null, 0, true)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void createRejectsDuplicateSlug() {
        when(categoryRepository.existsByName(any())).thenReturn(false);
        when(categoryRepository.existsBySlug("immunity")).thenReturn(true);

        assertThatThrownBy(() -> categoryService.create(
                new CategoryRequest("Immune Boosters", "immunity", null, 0, true)))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void deactivateSetsActiveFalse() {
        Category category = new Category("Immunity", "immunity", "d", 10, true);
        when(categoryRepository.findById(5L)).thenReturn(Optional.of(category));
        when(categoryRepository.save(any(Category.class))).thenAnswer(inv -> inv.getArgument(0));

        CategoryResponse result = categoryService.deactivate(5L);

        assertThat(result.active()).isFalse();
    }

    @Test
    void deactivateMissingCategoryIsNotFound() {
        when(categoryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> categoryService.deactivate(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void slugifyHandlesPunctuationAndCase() {
        assertThat(CategoryService.slugify("Personal Care")).isEqualTo("personal-care");
        assertThat(CategoryService.slugify("Hair & Skin")).isEqualTo("hair-and-skin");
        assertThat(CategoryService.slugify("  Churna!!  ")).isEqualTo("churna");
    }
}
