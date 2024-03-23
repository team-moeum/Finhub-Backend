package fotcamp.finhub.admin.dto.process;

import fotcamp.finhub.common.domain.Category;
import fotcamp.finhub.common.service.AwsS3Service;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AllCategoryProcessDto {
    private final Long id;
    private final String name;
    private final String thumbnailImgPath;
    private final String useYN;

    public AllCategoryProcessDto(Category category) {
        this.id = category.getId();
        this.name = category.getName();
        this.thumbnailImgPath = category.getThumbnailImgPath();
        this.useYN = category.getUseYN();
    }
}
