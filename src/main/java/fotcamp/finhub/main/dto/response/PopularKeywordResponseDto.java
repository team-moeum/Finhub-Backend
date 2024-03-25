package fotcamp.finhub.main.dto.response;


import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class PopularKeywordResponseDto {

    private String keyword;

    public PopularKeywordResponseDto(String keyword) {
        this.keyword = keyword;
    }
}
