package fotcamp.finhub.admin.service;

import fotcamp.finhub.admin.domain.GptLog;
import fotcamp.finhub.admin.domain.Manager;
import fotcamp.finhub.admin.dto.*;
import fotcamp.finhub.admin.repository.*;
import fotcamp.finhub.common.api.ApiResponseWrapper;
import fotcamp.finhub.common.domain.Category;
import fotcamp.finhub.common.domain.Gpt;
import fotcamp.finhub.common.domain.Topic;
import fotcamp.finhub.common.domain.UserType;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AdminService {

    private final ManagerRepository managerRepository;
    private final CategoryRepository categoryRepository;
    private final TopicRepository topicRepository;
    private final UserTypeRepository userTypeRepository;
    private final TopicRepositoryCustom topicRepositoryCustom;
    private final GptService gptService;
    private final GptLogRepository gptLogRepository;
    private final GptRepository gptRepository;

    // 로그인
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> login(LoginDto loginDto) {
        try {
            Manager manager = managerRepository.findByUserId(loginDto.getId()).orElseThrow(EntityNotFoundException::new);
            if (manager.getPassword().equals(loginDto.getPassword())) {
                return ResponseEntity.ok(ApiResponseWrapper.success()); // 200
            }
            // 비밀번호 틀렸을 경우
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponseWrapper.fail("비밀번호 틀림"));
        } catch (EntityNotFoundException e) { // 유저 아이디가 틀린 경우
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 아이디"));
        }
    }

    // 카테고리 전체 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getAllCategory() {
        List<Category> categories = categoryRepository.findAll();
        List<CategoryResponseDto> categoryResponseDtoList = categories.stream().map(CategoryResponseDto::new).toList();
        AllCategoryResponseDto allCategoryResponseDto = new AllCategoryResponseDto(categoryResponseDtoList);

        return ResponseEntity.ok(ApiResponseWrapper.success(allCategoryResponseDto));
    }

    // 카테고리 상세 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getDetailCategory(Long categoryId) {
        try {
            Category findCategory = categoryRepository.findById(categoryId).orElseThrow(EntityNotFoundException::new);

            List<Topic> topicList = findCategory.getTopics();
            List<DetailCategoryTopicResponseDto> detailCategoryTopicResponseDtos = topicList.stream().map(DetailCategoryTopicResponseDto::new).toList();

            DetailCategoryResponseDto detailCategoryResponseDto = new DetailCategoryResponseDto(findCategory, detailCategoryTopicResponseDtos);
            return ResponseEntity.ok(ApiResponseWrapper.success(detailCategoryResponseDto));

        } catch (EntityNotFoundException e) {
            log.error("존재하지 않는 카테고리입니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 카테고리"));
        }


    }

    // 카테고리 생성
    public ResponseEntity<ApiResponseWrapper> createCategory(CreateCategoryDto createCategoryDto) {
        try {
            // 중복 검사
            categoryRepository.findByName(createCategoryDto.getName()).ifPresent(e -> {
                throw new DuplicateKeyException("이미 존재하는 카테고리");
            });

            Category category = Category.builder()
                    .name(createCategoryDto.getName())
                    .thumbnailImgPath(createCategoryDto.getThumbnailImgPath())
                    .build();

            Category saveCategory = categoryRepository.save(category);

            return ResponseEntity.ok(ApiResponseWrapper.success(new CreateCategoryResponseDto(saveCategory.getId())));
        } catch (DuplicateKeyException e) {
            log.error("이미 존재하는 카테고리입니다.");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseWrapper.fail("이미 존재하는 카테고리"));
        }

    }

    // 카테고리 수정 (보이기 / 숨기기)
    public ResponseEntity<ApiResponseWrapper> modifyCategory(ModifyCategoryDto modifyCategoryDto) {
        try {
            // 없는 카테고리면 예외
            Category category = categoryRepository.findById(modifyCategoryDto.getId()).orElseThrow(EntityNotFoundException::new);

            // 수정할 카테고리명 중복 검사
            categoryRepository.findByName(modifyCategoryDto.getName()).ifPresent(e -> {
                if (!(e.getId().equals(modifyCategoryDto.getId()))) {
                    throw new DuplicateKeyException("이미 존재하는 카테고리");
                }
            });

            // useYN값 Y, N인지 판단
            if (!("Y".equals(modifyCategoryDto.getUseYN()) || "N".equals(modifyCategoryDto.getUseYN()))) {
                throw new IllegalArgumentException();
            }

            // 토픽 이름, 썸네일, useYN 수정
            category.modifyNameThumbnailUseYN(modifyCategoryDto);

            // 토픽 카테고리 수정
            List<ModifyTopicCategoryDto> topicList = modifyCategoryDto.getTopicList();
            for (ModifyTopicCategoryDto topicDto : topicList) {
                topicRepository.findById(topicDto.getTopicId()).ifPresent(topic -> {
                    categoryRepository.findById(topicDto.getCategoryId()).ifPresent(topic::changeCategory);
                });
            }

            return ResponseEntity.ok(ApiResponseWrapper.success());
        } catch (EntityNotFoundException e) {
            log.error("존재하지 않는 카테고리입니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 카테고리"));
        } catch (DuplicateKeyException e) {
            log.error("이미 존재하는 카테고리명입니다.");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseWrapper.fail("이미 존재하는 카테고리명"));
        } catch (IllegalArgumentException e) {
            log.error("useYN에 다른 값이 들어왔습니다.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponseWrapper.fail("Y, N 값 중 하나를 입력해주세요"));
        }
    }

    // 토픽 전체 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getAllTopic(Long categoryId, String useYN) {
        List<Topic> topicList = topicRepositoryCustom.searchAllTopicFilterList(categoryId, useYN);
        List<TopicResponseDto> topicResponseDtos = topicList.stream().map(TopicResponseDto::new).toList();
        AllTopicResponseDto resultDto = new AllTopicResponseDto(topicResponseDtos);

        return ResponseEntity.ok(ApiResponseWrapper.success(resultDto));
    }

    // 토픽 상세 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getDetailTopic(Long topicId) {
        try {
            Topic findTopic = topicRepository.findById(topicId).orElseThrow(EntityNotFoundException::new);
            List<DetailTopicGptResponseDto> detailTopicGptResponseDtos = findTopic.getGptList().stream().map(DetailTopicGptResponseDto::new).toList();
            DetailTopicResponseDto detailTopicResponseDto = new DetailTopicResponseDto(findTopic, detailTopicGptResponseDtos);

            return ResponseEntity.ok(ApiResponseWrapper.success(detailTopicResponseDto));
        } catch (EntityNotFoundException e) {
            log.error("존재하지 않는 토픽입니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 토픽"));
        }
    }

    // 토픽 생성
    public ResponseEntity<ApiResponseWrapper> createTopic(CreateTopicDto createTopicDto) {
        try {
            Category topicCategory = categoryRepository.findById(createTopicDto.getCategoryId()).orElseThrow(EntityNotFoundException::new);

            Topic topic = Topic.builder()
                    .title(createTopicDto.getTitle())
                    .definition(createTopicDto.getDefinition())
                    .shortDefinition(createTopicDto.getShortDefinition())
                    .thumbnailImgPath(createTopicDto.getThumbnail())
                    .createdBy(createTopicDto.getCreatedBy())
                    .build();

            topic.setCategory(topicCategory);
            Long topicId = topicRepository.save(topic).getId();

            return ResponseEntity.ok(ApiResponseWrapper.success(new CreateTopicResponseDto(topicId)));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 카테고리"));
        }
    }

    // 토픽 수정
    public ResponseEntity<ApiResponseWrapper> modifyTopic(ModifyTopicDto modifyTopicDto) {
        try {
            Topic topic = topicRepository.findById(modifyTopicDto.getTopicId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 토픽"));
            Category category = categoryRepository.findById(modifyTopicDto.getCategoryId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 카테고리"));
            // 토픽 내용 수정
            topic.modifyTopic(modifyTopicDto, category);
            List<GptDto> gptDtoList = modifyTopicDto.getGptList();
            for (GptDto gptDto : gptDtoList) {
                if (!("Y".equals(gptDto.getUseYN()) || "N".equals(gptDto.getUseYN()))) {
                    throw new IllegalArgumentException();
                }
                gptRepository.findById(gptDto.getGptId()).ifPresent(gpt -> {gpt.modifyContentUseYN(gptDto);});
            }
            return ResponseEntity.ok(ApiResponseWrapper.success());
        } catch (EntityNotFoundException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail(e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.error("useYN에 다른 값이 들어왔습니다.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponseWrapper.fail("Y, N 값 중 하나를 입력해주세요"));
        }



    }

    // 유저타입 전체 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getAllUserType() {
        List<UserType> userTypeList = userTypeRepository.findAll();
        List<UserTypeResponseDto> userTypeResponseDtos = userTypeList.stream().map(UserTypeResponseDto::new).toList();
        AllUserTypeResponseDto allUserTypeResponseDto = new AllUserTypeResponseDto(userTypeResponseDtos);

        return ResponseEntity.ok(ApiResponseWrapper.success(allUserTypeResponseDto));
    }

    // 유저타입 상세 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getDetailUserType(Long typeId) {
        try {
            UserType findUserType = userTypeRepository.findById(typeId).orElseThrow(EntityNotFoundException::new);
            DetailUserTypeResponseDto detailUserTypeResponseDto = new DetailUserTypeResponseDto(findUserType);

            return ResponseEntity.ok(ApiResponseWrapper.success(detailUserTypeResponseDto));

        } catch (EntityNotFoundException e) {
            log.error("존재하지 않는 유저타입입니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 유저타입"));
        }

    }

    // 유저타입 생성
    public ResponseEntity<ApiResponseWrapper> createUserType(CreateUserTypeDto createUserTypeDto) {
        try {
            // 중복 검사
            userTypeRepository.findByName(createUserTypeDto.getName()).ifPresent(e -> {
                throw new DuplicateKeyException("중복된 유저 타입");
            });

            UserType userType = UserType.builder()
                    .name(createUserTypeDto.getName())
                    .avatarImgPath(createUserTypeDto.getAvatar())
                    .build();

            Long usertypeId = userTypeRepository.save(userType).getId();

            CreateUserTypeResponseDto createUserTypeResponseDto = new CreateUserTypeResponseDto(usertypeId);

            return ResponseEntity.ok(ApiResponseWrapper.success(createUserTypeResponseDto));
        } catch (DuplicateKeyException e) {
            log.error("이미 존재하는 유저 타입입니다.");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseWrapper.fail("이미 존재하는 유저타입"));
        }
    }

    // 유저타입 수정
    public ResponseEntity<ApiResponseWrapper> modifyUserType(ModifyUserTypeDto modifyUserTypeDto) {
        try {
            // 없는 유저타입이면 예외
            UserType userType = userTypeRepository.findById(modifyUserTypeDto.getId()).orElseThrow(EntityNotFoundException::new);
            // 수정할 유저타입명이 이미 존재하는지 판단
            userTypeRepository.findByName(modifyUserTypeDto.getName()).ifPresent(e -> {
                if (!(e.getId().equals(modifyUserTypeDto.getId()))) {
                    throw new DuplicateKeyException("이미 존재하는 유저타입");
                }
            });
            // useYN값 Y, N인지 판단
            if (!("Y".equals(modifyUserTypeDto.getUseYN()) || "N".equals(modifyUserTypeDto.getUseYN()))) {
                throw new IllegalArgumentException();
            }
            userType.modifyUserType(modifyUserTypeDto);

            return ResponseEntity.ok(ApiResponseWrapper.success());
        } catch (EntityNotFoundException e) {
            log.error("존재하지 않는 유저타입 입니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 유저타입"));
        } catch (DuplicateKeyException e) {
            log.error("이미 존재하는 카테고리명입니다.");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseWrapper.fail("이미 존재하는 유저타입명"));
        } catch (IllegalArgumentException e) {
            log.error("useYN에 다른 값이 들어왔습니다.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponseWrapper.fail("Y, N 값 중 하나를 입력해주세요"));
        }
    }

    // GPT 답변 로그 저장 및 반환
    public ResponseEntity<ApiResponseWrapper> createGptContent(CreateGptContentDto createGptContentDto) {
        try {
            Topic topic = topicRepository.findById(createGptContentDto.getTopicId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 토픽"));
            UserType userType = userTypeRepository.findById(createGptContentDto.getUsertypeId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 유저타입"));
            Category category = topic.getCategory();

            String categoryName = category.getName();
            String topicTitle = topic.getTitle();
            String usertypeName = userType.getName();

            // 프롬프트 생성
            StringBuilder sb = new StringBuilder();
            sb.append(categoryName);
            sb.append("라는 금융카테고리 중 ");
            sb.append("\"");
            sb.append(topicTitle);
            sb.append("\"");
            sb.append("라는 질문에 대한 답변을 ");
            sb.append("\"");
            sb.append(usertypeName);
            sb.append("\"");
            sb.append("에게 알기 쉽게 설명해주고 싶어.");
            sb.append("\"");
            sb.append(usertypeName);
            sb.append("\"");
            sb.append("이 이해하기 쉽게 비유를 들어 설명해줘.");

            // GPT 답변 받기
            log.info("--gpt 실행 중---");
            log.info("prompt : " + sb.toString());
            String answer = gptService.saveLogAndReturnAnswer(sb.toString());
            log.info("---gpt 답변 완료---");
            log.info("answer : " + answer);

            // 로그 DB 저장
            log.info("---gpt log 저장 중---");
            GptLog gptLog = GptLog.builder()
                    .categoryId(category.getId())
                    .topicId(topic.getId())
                    .usertypeId(userType.getId())
                    .question(sb.toString())
                    .answer(answer)
                    .createdBy(createGptContentDto.getCreatedBy())
                    .build();

            gptLogRepository.save(gptLog);

            // GPT 답변 리턴
            return ResponseEntity.ok(ApiResponseWrapper.success(answer));
        } catch (EntityNotFoundException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail(e.getMessage()));
        } catch (Exception e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponseWrapper.fail(e.getMessage()));
        }

    }
}
