package fotcamp.finhub.common.service;

import fotcamp.finhub.admin.dto.request.SaveImgToS3RequestDto;
import fotcamp.finhub.common.utils.CommonUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetUrlRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.nio.file.NoSuchFileException;

@Slf4j
@RequiredArgsConstructor
@Component
public class AwsS3Service {
    private final S3Client s3Client;

    @Value("${spring.cloud.aws.s3.bucket}")
    private String bucketName;

    public String uploadFile(SaveImgToS3RequestDto saveImgToS3RequestDto) throws NoSuchFileException {

        if(saveImgToS3RequestDto.getFile().isEmpty()) {
            log.error("image is null");
            throw new NoSuchFileException("파일이 없습니다. 파일을 첨부해주세요.");
        }

        String fileName = getFileName(saveImgToS3RequestDto.getFile());
        // 파일 이름에 type을 포함시켜 폴더 구조를 생성
        String filePath = saveImgToS3RequestDto.getType() + "/" + fileName;
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .contentType(saveImgToS3RequestDto.getFile().getContentType())
                    .contentLength(saveImgToS3RequestDto.getFile().getSize())
                    .key(filePath)
                    .build();
            RequestBody requestBody = RequestBody.fromBytes(saveImgToS3RequestDto.getFile().getBytes());
            s3Client.putObject(putObjectRequest, requestBody);
        } catch (IOException e) {
            log.error("cannot upload image",e);
            throw new RuntimeException(e);
        }
        GetUrlRequest getUrlRequest = GetUrlRequest.builder()
                .bucket(bucketName)
                .key(filePath)
                .build();

        return s3Client.utilities().getUrl(getUrlRequest).toString() ;
    }

    public String getFileName(MultipartFile multipartFile) {
        return CommonUtils.buildFileName(multipartFile.getOriginalFilename());
    }
}
