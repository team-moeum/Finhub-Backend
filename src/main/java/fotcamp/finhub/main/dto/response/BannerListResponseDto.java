package fotcamp.finhub.main.dto.response;

import fotcamp.finhub.main.dto.process.BannerListProcessDto;

import java.util.List;

public record BannerListResponseDto(List<BannerListProcessDto> bannerList) {
}
