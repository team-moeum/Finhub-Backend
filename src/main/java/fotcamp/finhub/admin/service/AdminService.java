package fotcamp.finhub.admin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import fotcamp.finhub.admin.domain.GptLog;
import fotcamp.finhub.admin.domain.GptPrompt;
import fotcamp.finhub.admin.domain.Manager;
import fotcamp.finhub.admin.domain.ManagerRefreshToken;
import fotcamp.finhub.admin.dto.process.*;
import fotcamp.finhub.admin.dto.request.*;
import fotcamp.finhub.admin.dto.response.*;
import fotcamp.finhub.admin.repository.*;
import fotcamp.finhub.admin.service.gpt.GptService;
import fotcamp.finhub.common.api.ApiResponseWrapper;
import fotcamp.finhub.common.domain.*;
import fotcamp.finhub.common.dto.process.PageInfoProcessDto;
import fotcamp.finhub.common.security.CustomUserDetails;
import fotcamp.finhub.common.security.TokenDto;
import fotcamp.finhub.common.service.AwsS3Service;
import fotcamp.finhub.common.service.CommonService;
import fotcamp.finhub.common.utils.DateUtil;
import fotcamp.finhub.common.utils.JwtUtil;
import fotcamp.finhub.main.dto.process.AnnouncementProcessDto;
import fotcamp.finhub.main.dto.process.FcmMessageProcessDto;
import fotcamp.finhub.main.dto.process.ReportedCommentsProcessDto;
import fotcamp.finhub.main.dto.response.AnnouncementResponseDto;
import fotcamp.finhub.main.dto.response.column.AdminCommentResponseDto;
import fotcamp.finhub.main.dto.response.column.ReportCommentRequestDto;
import fotcamp.finhub.main.dto.response.column.ReportedCommentsResponseDto;
import fotcamp.finhub.main.repository.*;
import jakarta.mail.MessagingException;
import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.coyote.BadRequestException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.NoSuchFileException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Transactional
@Slf4j
public class AdminService {

    private final CategoryRepository categoryRepository;
    private final CategoryRepositoryCustom categoryRepositoryCustom;
    private final TopicRepository topicRepository;
    private final UserTypeRepository userTypeRepository;
    private final UserTypeRepositoryCustom userTypeRepositoryCustom;
    private final TopicRepositoryCustom topicRepositoryCustom;
    private final GptService gptService;
    private final GptLogRepository gptLogRepository;
    private final GptLogRepositoryCustom gptLogRepositoryCustom;
    private final GptPromptRepository gptPromptRepository;
    private final GptRepository gptRepository;
    private final AwsS3Service awsS3Service;
    private final CommonService commonService;
    private final ManagerRefreshRepository managerRefreshRepository;
    private final JwtUtil jwtUtil;
    private final ManagerRepository managerRepository;
    private final QuizRepository quizRepository;
    private final TopicQuizRepository topicQuizRepository;
    private final BannerRepository bannerRepository;
    private final BannerRepositoryCustom bannerRepositoryCustom;
    private final TopicRequestRepository topicRequestRepository;
    private final TopicRequestRepositoryCustom topicRequestRepositoryCustom;
    private final UserAvatarRepository userAvatarRepository;
    private final CalendarEmoticonRepository calendarEmoticonRepository;
    private final GptColumnRepository gptColumnRepository;
    private final TopicGptColumnRepository topicGptColumnRepository;
    private final AnnouncementRepository announcementRepository;
    private final ReportReasonsRepository reportReasonsRepository;
    private final CommentsReportRepositoryCustom commentsReportRepositoryCustom;
    private final CommentsReportRepository commentsReportRepository;
    private final CommentsRepository commentsRepository;
    private final FeedbackRepository feedbackRepository;

    private final FcmService fcmService;
    private final EmailService emailService;

    @Value("${promise.category}") String promiseCategory;
    @Value("${promise.topic}") String promiseTopic;
    @Value("${promise.usertype}") String promiseUsertype;

    // 로그인
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> login(LoginRequestDto loginRequestDto) {
        try {
            Manager manager = managerRepository.findByEmail(loginRequestDto.email()).orElseThrow(EntityNotFoundException::new);

            if (manager.getPassword().equals(loginRequestDto.password())) {
                TokenDto allTokens = jwtUtil.createAllTokens(manager.getManagerUuid(), manager.getRole().toString(), "admin");
                // 이미 존재하는 이메일인 경우 해당 레코드를 업데이트하고, 아닌 경우 새로운 레코드 추가
                Optional<ManagerRefreshToken> refreshToken = managerRefreshRepository.findByEmail(manager.getEmail());
                if (refreshToken.isPresent()) {
                    refreshToken.get().updateToken(allTokens.getRefreshToken());
                    managerRefreshRepository.save(refreshToken.get());
                } else {
                    ManagerRefreshToken managerRefreshToken = new ManagerRefreshToken(allTokens.getRefreshToken(), manager.getEmail());
                    managerRefreshRepository.save(managerRefreshToken);
                }
                AdminLoginResponseDto responseDto = new AdminLoginResponseDto(manager.getRole(), allTokens);
                return ResponseEntity.ok(ApiResponseWrapper.success(responseDto)); // 200
            }
            // 비밀번호 틀렸을 경우
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponseWrapper.fail("비밀번호가 틀렸습니다."));
        } catch (EntityNotFoundException e) { // 유저 아이디가 틀린 경우
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 아이디입니다."));
        }
    }

    // 카테고리 전체 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getAllCategory(Pageable pageable, String useYN) {
        Page<Category> categories = categoryRepositoryCustom.searchAllCategoryFilterList(pageable, useYN);
        List<AllCategoryProcessDto> allCategoryProcessDtoList = categories.getContent().stream().map(AllCategoryProcessDto::new).toList();
        PageInfoProcessDto PageInfoProcessDto = commonService.setPageInfo(categories);
        AllCategoryResponseDto allCategoryResponseDto = new AllCategoryResponseDto(allCategoryProcessDtoList, PageInfoProcessDto);

        return ResponseEntity.ok(ApiResponseWrapper.success(allCategoryResponseDto));
    }

    // 카테고리 상세 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getDetailCategory(Long categoryId) {
        try {
            Category findCategory = categoryRepository.findById(categoryId).orElseThrow(EntityNotFoundException::new);

            // 썸네일 이미지 경로 수정
            String modifiedThumbnailImgPath = awsS3Service.combineWithCloudFrontBaseUrl(findCategory.getThumbnailImgPath());

            List<Topic> topicList = findCategory.getTopics();
            List<DetailCategoryTopicProcessDto> detailCategoryTopicProcessDtos = topicList.stream().map(DetailCategoryTopicProcessDto::new).toList();

            // 수정된 썸네일 경로를 사용하여 DTO 생성
            DetailCategoryResponseDto detailCategoryResponseDto = new DetailCategoryResponseDto(findCategory.getId(), findCategory.getName(), modifiedThumbnailImgPath, findCategory.getUseYN(), detailCategoryTopicProcessDtos);
            return ResponseEntity.ok(ApiResponseWrapper.success(detailCategoryResponseDto));

        } catch (EntityNotFoundException e) {
            log.error("존재하지 않는 카테고리입니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 카테고리"));
        }


    }

    // 카테고리 생성
    public ResponseEntity<ApiResponseWrapper> createCategory(CreateCategoryRequestDto createCategoryRequestDto) {
        try {
            // 중복 검사
            categoryRepository.findByName(createCategoryRequestDto.name()).ifPresent(e -> {
                throw new DuplicateKeyException("이미 존재하는 카테고리");
            });

            Category category = Category.builder()
                    .name(createCategoryRequestDto.name())
                    .thumbnailImgPath(awsS3Service.extractPathFromUrl(createCategoryRequestDto.s3ImgUrl()))
                    .position(categoryRepository.findMaxPosition()+1)
                    .build();

            Category saveCategory = categoryRepository.save(category);

            return ResponseEntity.ok(ApiResponseWrapper.success(new CreateCategoryResponseDto(saveCategory.getId())));
        } catch (DuplicateKeyException e) {
            log.error("이미 존재하는 카테고리입니다.");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseWrapper.fail("이미 존재하는 카테고리"));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponseWrapper.fail(e.getMessage()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    // 카테고리 수정
    public ResponseEntity<ApiResponseWrapper> modifyCategory(ModifyCategoryRequestDto modifyCategoryRequestDto) {
        try {
            // 없는 카테고리면 예외
            Category category = categoryRepository.findById(modifyCategoryRequestDto.id()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 카테고리"));

            // 수정할 카테고리명 중복 검사
            categoryRepository.findByName(modifyCategoryRequestDto.name()).ifPresent(e -> {
                if (!(e.getId().equals(modifyCategoryRequestDto.id()))) {
                    throw new DuplicateKeyException("이미 존재하는 카테고리");
                }
            });

            // useYN값 Y, N인지 판단
            if (!("Y".equals(modifyCategoryRequestDto.useYN()) || "N".equals(modifyCategoryRequestDto.useYN()))) {
                throw new IllegalArgumentException();
            }

            ModifyCategoryRequestDto modifiedDto = new ModifyCategoryRequestDto(modifyCategoryRequestDto.id(), modifyCategoryRequestDto.name(), modifyCategoryRequestDto.useYN(), awsS3Service.extractPathFromUrl(modifyCategoryRequestDto.s3ImgUrl()), modifyCategoryRequestDto.topicList());

            // 토픽 이름, 썸네일, useYN 수정
            category.modifyNameUseYNImg(modifiedDto);
            categoryRepository.save(category);

            // 토픽 카테고리 수정
            List<ModifyTopicCategoryProcessDto> topicList = modifyCategoryRequestDto.topicList();
            for (ModifyTopicCategoryProcessDto topicDto : topicList) {
                Topic topic = topicRepository.findById(topicDto.topicId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 토픽입니다."));
                Category afterCategory = categoryRepository.findById(topicDto.categoryId()).orElseThrow(() -> new EntityNotFoundException("변경하려는 카테고리가 존재하지 않습니다."));
                topic.changeCategory(afterCategory);
                topicRepository.save(topic);
            }

            return ResponseEntity.ok(ApiResponseWrapper.success());
        } catch (EntityNotFoundException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail(e.getMessage()));
        } catch (DuplicateKeyException e) {
            log.error("이미 존재하는 카테고리명입니다.");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseWrapper.fail("이미 존재하는 카테고리명"));
        } catch (IllegalArgumentException e) {
            log.error("useYN에 다른 값이 들어왔습니다.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponseWrapper.fail("Y, N 값 중 하나를 입력해주세요"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 토픽 전체 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getAllTopic(Pageable pageable, Long categoryId, String useYN) {
        Page<Topic> topics = topicRepositoryCustom.searchAllTopicFilterList(pageable, categoryId, useYN);
        List<TopicProcessDto> topicProcessDtos = topics.getContent().stream().map(TopicProcessDto::new).toList();
        PageInfoProcessDto pageInfoProcessDto = commonService.setPageInfo(topics);
        AllTopicResponseDto resultDto = new AllTopicResponseDto(topicProcessDtos, pageInfoProcessDto);
        return ResponseEntity.ok(ApiResponseWrapper.success(resultDto));
    }

    // 토픽 상세 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getDetailTopic(Long topicId) {
        try {
            Topic findTopic = topicRepository.findById(topicId).orElseThrow(EntityNotFoundException::new);
            List<DetailTopicProcessDto> detailTopicProcessDtos = findTopic.getGptList().stream()
                    .sorted(Comparator.comparing(gpt -> gpt.getUserType().getId()))
                    .map(DetailTopicProcessDto::new).toList();
            DetailTopicResponseDto detailTopicResponseDto = new DetailTopicResponseDto(findTopic.getCategory().getId(), findTopic.getId(),
                    findTopic.getTitle(), findTopic.getDefinition(), findTopic.getSummary(),
                    awsS3Service.combineWithCloudFrontBaseUrl(findTopic.getThumbnailImgPath()), findTopic.getUseYN(), detailTopicProcessDtos);

            return ResponseEntity.ok(ApiResponseWrapper.success(detailTopicResponseDto));
        } catch (EntityNotFoundException e) {
            log.error("존재하지 않는 토픽입니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 토픽"));
        }
    }

    // 토픽 생성
    public ResponseEntity<ApiResponseWrapper> createTopic(CreateTopicRequestDto createTopicRequestDto, CustomUserDetails userDetails) {
        try {
            Category topicCategory = categoryRepository.findById(createTopicRequestDto.categoryId()).orElseThrow(EntityNotFoundException::new);

            Topic topic = Topic.builder()
                    .title(createTopicRequestDto.title())
                    .definition(createTopicRequestDto.definition())
                    .summary(createTopicRequestDto.summary())
                    .thumbnailImgPath(awsS3Service.extractPathFromUrl(createTopicRequestDto.s3ImgUrl()))
                    .createdBy(userDetails.getRole())
                    .position(topicRepository.findMaxPosition()+1)
                    .build();

            topic.setCategory(topicCategory);
            Long topicId = topicRepository.save(topic).getId();

            return ResponseEntity.ok(ApiResponseWrapper.success(new CreateTopicResponseDto(topicId)));
        } catch (EntityNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 카테고리"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 토픽 수정
    public ResponseEntity<ApiResponseWrapper> modifyTopic(ModifyTopicRequestDto modifyTopicRequestDto,
                                                          CustomUserDetails userDetails) {
        try {
            Topic topic = topicRepository.findById(modifyTopicRequestDto.getTopicId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 토픽"));
            Category category = categoryRepository.findById(modifyTopicRequestDto.getCategoryId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 카테고리"));
            // 토픽 내용 수정
            topic.modifyTopic(modifyTopicRequestDto.getTitle(), modifyTopicRequestDto.getDefinition(), modifyTopicRequestDto.getSummary()
                    ,awsS3Service.extractPathFromUrl(modifyTopicRequestDto.getS3ImgUrl()),
                    category, userDetails.getRole(), modifyTopicRequestDto.getUseYN());

            List<Gpt> updateGptList = new ArrayList<>();
            List<GptProcessDto> gptProcessDtoList = modifyTopicRequestDto.getGptList();
            for (GptProcessDto gptProcessDto : gptProcessDtoList) {
                if (!("Y".equals(gptProcessDto.getUseYN()) || "N".equals(gptProcessDto.getUseYN()))) {
                    throw new IllegalArgumentException();
                }
                // gpt id null 일 때 gpt save 추가
                if (gptProcessDto.getGptId() == null) {
                    UserType userType = userTypeRepository.findById(gptProcessDto.getUsertypeId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 유저타입"));
                    Gpt newGpt = Gpt.builder()
                            .categoryId(modifyTopicRequestDto.getCategoryId())
                            .topic(topic)
                            .userType(userType)
                            .content(gptProcessDto.getContent())
                            .useYN(gptProcessDto.getUseYN())
                            .createdBy(userDetails.getRole())
                            .build();
                    gptRepository.save(newGpt);
                    updateGptList.add(newGpt);
                }
                if (gptProcessDto.getGptId() != null) {
                    // gpt entity 내용 수정
                    gptRepository.findById(gptProcessDto.getGptId()).ifPresent(gpt -> {
                        gpt.modifyContentUseYN(gptProcessDto, userDetails.getRole(), modifyTopicRequestDto.getCategoryId());
                        updateGptList.add(gpt);
                    });
                }
                topic.changeGptList(updateGptList);

            }
            return ResponseEntity.ok(ApiResponseWrapper.success());
        } catch (EntityNotFoundException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail(e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.error("useYN에 다른 값이 들어왔습니다.");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponseWrapper.fail("Y, N 값 중 하나를 입력해주세요"));
        } catch (Exception e) {
            log.error(e.getMessage());
            throw new RuntimeException(e);
        }
    }

    // 유저타입 전체 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getAllUserType(Pageable pageable, String useYN) {
        Page<UserType> userTypes = userTypeRepositoryCustom.searchAllUserTypeFilterList(pageable, useYN);
        List<UserTypeProcessDto> userTypeProcessDtos = userTypes.getContent().stream().map(userType -> {
            return new UserTypeProcessDto(userType.getId(), userType.getName(), userType.getUseYN(), awsS3Service.combineWithCloudFrontBaseUrl(userType.getAvatarImgPath()));
        }).toList();
        PageInfoProcessDto pageInfoProcessDto = commonService.setPageInfo(userTypes);
        AllUserTypeResponseDto allUserTypeResponseDto = new AllUserTypeResponseDto(userTypeProcessDtos, pageInfoProcessDto);

        return ResponseEntity.ok(ApiResponseWrapper.success(allUserTypeResponseDto));
    }

    // 유저타입 상세 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getDetailUserType(Long typeId) {
        try {
            UserType findUserType = userTypeRepository.findById(typeId).orElseThrow(EntityNotFoundException::new);
            DetailUserTypeResponseDto detailUserTypeResponseDto = new DetailUserTypeResponseDto(findUserType.getId(), findUserType.getName(),
                    awsS3Service.combineWithCloudFrontBaseUrl(findUserType.getAvatarImgPath()), findUserType.getUseYN());

            return ResponseEntity.ok(ApiResponseWrapper.success(detailUserTypeResponseDto));

        } catch (EntityNotFoundException e) {
            log.error("존재하지 않는 유저타입입니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 유저타입"));
        }

    }

    // 유저타입 생성
    public ResponseEntity<ApiResponseWrapper> createUserType(CreateUserTypeRequestDto createUserTypeRequestDto) {
        try {
            // 중복 검사
            userTypeRepository.findByName(createUserTypeRequestDto.name()).ifPresent(e -> {
                throw new DuplicateKeyException("중복된 유저 타입");
            });

            UserType userType = UserType.builder()
                    .name(createUserTypeRequestDto.name())
                    .avatarImgPath(awsS3Service.extractPathFromUrl(createUserTypeRequestDto.s3ImgUrl()))
                    .build();

            Long usertypeId = userTypeRepository.save(userType).getId();

            CreateUserTypeResponseDto createUserTypeResponseDto = new CreateUserTypeResponseDto(usertypeId);

            return ResponseEntity.ok(ApiResponseWrapper.success(createUserTypeResponseDto));
        } catch (DuplicateKeyException e) {
            log.error("이미 존재하는 유저 타입입니다.");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseWrapper.fail("이미 존재하는 유저타입"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 유저타입 수정
    public ResponseEntity<ApiResponseWrapper> modifyUserType(ModifyUserTypeRequestDto modifyUserTypeRequestDto) {
        try {
            // 없는 유저타입이면 예외
            UserType userType = userTypeRepository.findById(modifyUserTypeRequestDto.id()).orElseThrow(EntityNotFoundException::new);
            // 수정할 유저타입명이 이미 존재하는지 판단
            userTypeRepository.findByName(modifyUserTypeRequestDto.name()).ifPresent(e -> {
                if (!(e.getId().equals(modifyUserTypeRequestDto.id()))) {
                    throw new DuplicateKeyException("이미 존재하는 유저타입");
                }
            });
            // useYN값 Y, N인지 판단
            if (!("Y".equals(modifyUserTypeRequestDto.useYN()) || "N".equals(modifyUserTypeRequestDto.useYN()))) {
                throw new IllegalArgumentException();
            }
            userType.modifyUserType(modifyUserTypeRequestDto.name(), modifyUserTypeRequestDto.useYN(), awsS3Service.extractPathFromUrl(modifyUserTypeRequestDto.s3ImgUrl()));

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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 토픽 유저타입 내용 GPT 질문 답변 로그 저장 및 답변 반환
    public ResponseEntity<ApiResponseWrapper> createTopicUsertypeGptContent(CreateGptContentRequestDto createGptContentRequestDto,
                                                               CustomUserDetails userDetails) {
        try {
            Topic topic = topicRepository.findById(createGptContentRequestDto.topicId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 토픽"));
            UserType userType = userTypeRepository.findById(createGptContentRequestDto.usertypeId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 유저타입"));
            Category category = categoryRepository.findById(createGptContentRequestDto.categoryId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 카테고리"));

            String categoryName = category.getName();
            String topicTitle = topic.getTitle();
            String usertypeName = userType.getName();

            // 최신 프롬프트 가져오기 from DB
            GptPrompt gptPrompt = gptPromptRepository.findFirstByOrderByIdDesc().orElseThrow(() -> new EntityNotFoundException("프롬프트가 존재하지 않음"));
            String prompt = gptPrompt.getPrompt();

            // 프롬프트 약속 단어 치환 (TO-BE : 약속 더 만들어야 할 것 같음)
            String resultPrompt = prompt.replaceAll(promiseCategory, categoryName)
                                        .replaceAll(promiseTopic, topicTitle)
                                        .replaceAll(promiseUsertype, usertypeName);
            // GPT 답변 받기
            log.info("--gpt 실행 중---");
            log.info("prompt : " + resultPrompt);
            String gptAnswer = gptService.returnGptAnswer(resultPrompt);
            log.info("---gpt 답변 완료---");
            log.info("answer : " + gptAnswer);

            // 로그 DB 저장
            log.info("---gpt log 저장 중---");
            GptLog gptLog = GptLog.builder()
                    .categoryId(category.getId())
                    .topicId(topic.getId())
                    .usertypeId(userType.getId())
                    .question(resultPrompt)
                    .answer(gptAnswer)
                    .createdBy(userDetails.getRole())
                    .build();

            gptLogRepository.save(gptLog);

            String prefix = "[설명] : ";

            // '[요약] :' 문자열 뒤의 내용을 추출
            int summaryStart = gptAnswer.indexOf(prefix);

            if (summaryStart != -1) {
                String summary = gptAnswer.substring(summaryStart + prefix.length()).trim();
                return ResponseEntity.ok(ApiResponseWrapper.success(new GptResponseDto(summary)));
            } else {
                return ResponseEntity.internalServerError().body(ApiResponseWrapper.fail("GPT 답변 파싱 실패", gptAnswer));
            }
        } catch (EntityNotFoundException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail(e.getMessage()));
        } catch (Exception e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponseWrapper.fail(e.getMessage()));
        }
    }

    // 타입 분류에 따른 s3에 이미지 저장 후 이미지 url 반환
    public ResponseEntity<ApiResponseWrapper> saveImgToS3(SaveImgToS3RequestDto saveImgToS3RequestDto) {
        try {
            String imgUrl = awsS3Service.uploadFile(saveImgToS3RequestDto);
            return ResponseEntity.ok(ApiResponseWrapper.success(new S3ImgUrlResponseDto(imgUrl)));
        } catch (NoSuchFileException e) {
            log.error("File not found exception", e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponseWrapper.fail("파일이 없습니다. 파일을 첨부해주세요."));
        } catch (RuntimeException e) {
            log.error("Runtime exception", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponseWrapper.fail("내부 서버 오류가 발생했습니다."));
        }
    }

    // gpt 프롬프트 저장
    public ResponseEntity<ApiResponseWrapper> saveGptPrompt(SaveGptPromptRequestDto saveGptPromptRequestDto,
                                                            CustomUserDetails userDetails) {
        try {
            GptPrompt prompt = GptPrompt.builder()
                    .prompt(saveGptPromptRequestDto.prompt())
                    .createdBy(userDetails.getRole())
                    .build();
            gptPromptRepository.save(prompt);
            return ResponseEntity.ok(ApiResponseWrapper.success());
        } catch (Exception e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponseWrapper.fail(e.getMessage()));
        }

    }

    // gpt 프롬프트 최신 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getGptPrompt() {
        try {
            GptPrompt gptPrompt = gptPromptRepository.findFirstByOrderByIdDesc().orElseThrow(EntityNotFoundException::new);
            List<RecentPromptProcessDto> promiseList = new ArrayList<>();
            promiseList.add(new RecentPromptProcessDto("category", promiseCategory));
            promiseList.add(new RecentPromptProcessDto("topic", promiseTopic));
            promiseList.add(new RecentPromptProcessDto("usertype", promiseUsertype));
            RecentPromptResponseDto recentPromptResponseDto = new RecentPromptResponseDto(gptPrompt, promiseList);

            return ResponseEntity.ok(ApiResponseWrapper.success(recentPromptResponseDto));
        } catch (EntityNotFoundException e) {
            log.error("프롬프트가 db에 존재하지 않습니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("no prompt"));
        }
    }

    // gpt 질문 답변 로그 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getGptLog(Pageable pageable, Long topicId, Long usertypeId) {
        try {
            if (topicId != null) {
                topicRepository.findById(topicId).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 토픽"));
            }
            if (usertypeId != null) {
                userTypeRepository.findById(usertypeId).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 유저타입"));
            }
            Page<GptLog> gptLogs = gptLogRepositoryCustom.searchAllGptLogFilterList(pageable, topicId, usertypeId);
            List<GptLogProcessDto> gptLogProcessDtos = gptLogs.getContent().stream()
                    .filter(gptLog -> {
                        // 필터링 조건을 여기에 추가
                        return categoryRepository.existsById(gptLog.getCategoryId()) &&
                                topicRepository.existsById(gptLog.getTopicId()) &&
                                userTypeRepository.existsById(gptLog.getUsertypeId());
                    })
                    .map(gptLog -> {
                        String categoryName = categoryRepository.findById(gptLog.getCategoryId()).get().getName();
                        String topicTitle = topicRepository.findById(gptLog.getTopicId()).get().getTitle();
                        String usertypeName = userTypeRepository.findById(gptLog.getUsertypeId()).get().getName();

                        return new GptLogProcessDto(gptLog, categoryName, topicTitle, usertypeName);
                    }).toList();
            PageInfoProcessDto pageInfoProcessDto = commonService.setPageInfo(gptLogs);
            AllGptLogResponseDto allGptLogResponseDto = new AllGptLogResponseDto(gptLogProcessDtos, pageInfoProcessDto);

            return ResponseEntity.ok(ApiResponseWrapper.success(allGptLogResponseDto));
        } catch (EntityNotFoundException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail(e.getMessage()));
        }


    }

    // 퀴즈 생성
    public ResponseEntity<ApiResponseWrapper> createQuiz(CreateQuizRequestDto createQuizRequestDto,
                                                         CustomUserDetails userDetails) {
        try {
            List<Long> topicList = createQuizRequestDto.getTopicList();
            LocalDate targetDate = DateUtil.convertToDate(createQuizRequestDto.getYear(), createQuizRequestDto.getMonth(), createQuizRequestDto.getDay());
            // 퀴즈에 이미 존재하는 tagetDate일 경우 에러 반환
            if (quizRepository.existsByTargetDate(targetDate)) {
                throw new BadRequestException();
            }
            // 모든 토픽이 존재하는지 확인
            if (!topicList.isEmpty()) {
                for (Long topicId : topicList) {
                    Topic topic = topicRepository.findById(topicId).orElseThrow(EntityNotFoundException::new);
                }
            }
            Quiz quiz = Quiz.builder()
                    .question(createQuizRequestDto.getQuestion())
                    .answer(createQuizRequestDto.getAnswer())
                    .comment(createQuizRequestDto.getComment())
                    .targetDate(targetDate)
                    .createdBy(userDetails.getRole())
                    .build();

            quizRepository.save(quiz);


            for (Long topicId : topicList) {
                Topic topic = topicRepository.findById(topicId).get();

                TopicQuiz topicQuiz = TopicQuiz.builder()
                        .topic(topic)
                        .quiz(quiz)
                        .build();

                topicQuizRepository.save(topicQuiz);
                quiz.addTopicQuizList(topicQuiz);
            }

            return ResponseEntity.ok(ApiResponseWrapper.success(new CreateQuizResponseDto(quiz.getId())));
        } catch (EntityNotFoundException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 토픽입니다"));
        } catch (BadRequestException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponseWrapper.fail("이미 존재하는 날짜입니다."));
        }
    }

    // 퀴즈 월별 전체 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getMonthlyQuiz(String year, String month) {
        LocalDate startDate = DateUtil.convertToDate(Long.parseLong(year), Long.parseLong(month), 1L);
        LocalDate endDate = startDate.withDayOfMonth(startDate.lengthOfMonth());
        // targetDate 기준으로 오름차순 정렬
        Sort sort = Sort.by("targetDate").ascending();
        List<QuizProcessDto> quizProcessDtos = quizRepository.findByTargetDateBetween(startDate, endDate, sort).stream().map(QuizProcessDto::new).toList();
        GetMonthlyQuizResponseDto resultDto = new GetMonthlyQuizResponseDto(quizProcessDtos);

        return ResponseEntity.ok(ApiResponseWrapper.success(resultDto));
    }

    // 퀴즈 일 상세 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getDailyQuiz(String year, String month, String day) {
        try {
            LocalDate targetDate = DateUtil.convertToDate(Long.parseLong(year), Long.parseLong(month), Long.parseLong(day));
            Quiz quiz = quizRepository.findByTargetDate(targetDate).orElseThrow(EntityNotFoundException::new);
            GetDailyQuizResponseDto resultDto = new GetDailyQuizResponseDto(quiz);

            return ResponseEntity.ok(ApiResponseWrapper.success(new DailyQuizResponseDto(resultDto)));
        } catch (EntityNotFoundException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("퀴즈가 존재하지 않는 날입니다"));
        } catch (IncorrectResultSizeDataAccessException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponseWrapper.fail("동일 날짜에 퀴즈가 2개 있습니다."));
        }

    }

    // 퀴즈 수정
    public ResponseEntity<ApiResponseWrapper> modifyQuiz(ModifyQuizRequestDto modifyQuizRequestDto, CustomUserDetails userDetails) {
        try {
            LocalDate targetDate = DateUtil.convertToDate(modifyQuizRequestDto.getYear(), modifyQuizRequestDto.getMonth(), modifyQuizRequestDto.getDay());
            // targetDate를 기준으로 Quiz 엔티티 찾기
            Quiz quiz = quizRepository.findByTargetDate(targetDate)
                    .orElseThrow(() -> new EntityNotFoundException("해당 날짜에 대한 퀴즈가 존재하지 않습니다: " + targetDate));

            // ID를 기준으로 Quiz 엔티티 찾기
            Quiz findQuiz = quizRepository.findById(modifyQuizRequestDto.getId())
                    .orElseThrow(() -> new EntityNotFoundException("해당 ID를 가진 퀴즈가 존재하지 않습니다: " + modifyQuizRequestDto.getId()));

            // 두 Quiz 엔티티의 ID가 다를 경우 예외 처리
            if (!quiz.getId().equals(findQuiz.getId())) {
                throw new IllegalArgumentException("요청된 퀴즈 ID와 날짜에 해당하는 퀴즈 ID가 일치하지 않습니다.");
            }

            // 모든 토픽이 존재하는지 확인
            List<Long> topicList = modifyQuizRequestDto.getTopicList();
            if (!topicList.isEmpty()) {
                for (Long topicId : topicList) {
                    Topic topic = topicRepository.findById(topicId).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 토픽: " + topicId));
                }
            }

            // 기존 TopicQuiz 삭제
            topicQuizRepository.deleteAllByQuizId(quiz.getId());
            // 새로운 TopicQuiz 생성 후 quiz에 저장
            for (Long topicId : topicList) {
                Topic topic = topicRepository.findById(topicId).get();

                TopicQuiz topicQuiz = TopicQuiz.builder()
                        .topic(topic)
                        .quiz(quiz)
                        .build();

                topicQuizRepository.save(topicQuiz);
                // 퀴즈 토픽 리스트 수정
                quiz.addTopicQuizList(topicQuiz);
            }
            // 퀴즈 나머지 데이터 수정
            quiz.modifyQuiz(modifyQuizRequestDto, userDetails.getRole());

            return ResponseEntity.ok(ApiResponseWrapper.success());
        } catch (EntityNotFoundException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail(e.getMessage()));
        } catch (IllegalArgumentException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponseWrapper.fail(e.getMessage()));
        }
    }

    // 배너 생성
    public ResponseEntity<ApiResponseWrapper> createBanner(CreateBannerRequestDto createBannerRequestDto, CustomUserDetails userDetails) {
        try {
            Banner banner = Banner.builder()
                    .title(createBannerRequestDto.getTitle())
                    .subTitle(createBannerRequestDto.getSubTitle())
                    .landingPageUrl(createBannerRequestDto.getLandingPageUrl())
                    .bannerImageUrl(awsS3Service.extractPathFromUrl(createBannerRequestDto.getS3ImgUrl()))
                    .bannerType((createBannerRequestDto.getBannerType()))
                    .createdBy(userDetails.getRole())
                    .useYN(createBannerRequestDto.getUseYN())
                    .build();
            Banner saveBanner = bannerRepository.save(banner);
            return ResponseEntity.ok(ApiResponseWrapper.success(new CreateBannerResponseDto(saveBanner.getId())));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    // 배너 수정
    public ResponseEntity<ApiResponseWrapper> modifyBanner(ModifyBannerRequestDto modifyBannerRequestDto, CustomUserDetails userDetails) {
        try {
            Banner banner = bannerRepository.findById(modifyBannerRequestDto.getId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 배너"));
            banner.modifyBanner(modifyBannerRequestDto.getTitle(), modifyBannerRequestDto.getSubTitle(), modifyBannerRequestDto.getLandingPageUrl(), modifyBannerRequestDto.getBannerType(),
                    awsS3Service.extractPathFromUrl(modifyBannerRequestDto.getS3ImgUrl()), modifyBannerRequestDto.getUseYN(), userDetails.getRole());

            return ResponseEntity.ok(ApiResponseWrapper.success());
        } catch (EntityNotFoundException e) {
            log.error(e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail(e.getMessage()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 배너 전체 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getAllBanner(Pageable pageable, String useYN) {
        Page<Banner> banners = bannerRepositoryCustom.searchAllBannerFilterList(pageable, useYN);
        List<BannerProcessDto> bannerProcessDtos = banners.getContent().stream().map(BannerProcessDto::new).toList();
        PageInfoProcessDto pageInfoProcessDto = commonService.setPageInfo(banners);
        AllBannerResponseDto allBannerResponseDto = new AllBannerResponseDto(bannerProcessDtos, pageInfoProcessDto);

        return ResponseEntity.ok(ApiResponseWrapper.success(allBannerResponseDto));

    }

    // 배너 상세 조회
    @Transactional(readOnly = true)
    public ResponseEntity<ApiResponseWrapper> getDetailBanner(Long bannerId) {
        try {
            Banner findBanner = bannerRepository.findById(bannerId).orElseThrow(EntityNotFoundException::new);
            DetailBannerResponseDto detailBannerResponseDto = new DetailBannerResponseDto(
                    findBanner.getId(), findBanner.getTitle(), findBanner.getSubTitle(),
                    findBanner.getLandingPageUrl(), findBanner.getBannerType(), findBanner.getUseYN(), findBanner.getCreatedBy(),
                    awsS3Service.combineWithCloudFrontBaseUrl(findBanner.getBannerImageUrl()), findBanner.getCreatedTime(), findBanner.getModifiedTime()
            );

            return ResponseEntity.ok(ApiResponseWrapper.success(detailBannerResponseDto));

        } catch (EntityNotFoundException e) {
            log.error("존재하지 않는 배너입니다.");
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponseWrapper.fail("존재하지 않는 배너"));
        }
    }

    // 생성/수정 취소 (s3 이미지 삭제)
    public ResponseEntity<ApiResponseWrapper> deleteS3Image(DeleteS3ImageRequestDto deleteS3ImageRequestDto) {
        try {
            awsS3Service.deleteImageFromS3(deleteS3ImageRequestDto.s3ImgUrl());
            return ResponseEntity.ok(ApiResponseWrapper.success("이미지 삭제 성공"));
        } catch (RuntimeException e) {
            log.error("Failed to delete S3 image: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiResponseWrapper.fail("이미지 삭제 실패"));
        }
    }

     // 없는 단어 요청 한 것 확인하기
    public ResponseEntity<ApiResponseWrapper> getNoWordList(Pageable pageable, String resolvedYN) {
        Page<TopicRequest> topicRequests = topicRequestRepositoryCustom.searchAllTopicRequestFilterList(pageable, resolvedYN);
        List<AllTopicRequestProcessDto> allTopicRequestProcessDtoList = topicRequests.getContent().stream().map(AllTopicRequestProcessDto::new).toList();
        PageInfoProcessDto PageInfoProcessDto = commonService.setPageInfo(topicRequests);
        AllTopicRequestResponseDto allTopicRequestResponseDto = new AllTopicRequestResponseDto(allTopicRequestProcessDtoList, PageInfoProcessDto);

        return ResponseEntity.ok(ApiResponseWrapper.success(allTopicRequestResponseDto));
    }

    // 없는 단어 요청 시 체크하기
    public ResponseEntity<ApiResponseWrapper> checkNoWord(CheckNoWordRequestDto checkNoWordRequestDto) {
        TopicRequest topicRequest = topicRequestRepository.findById(checkNoWordRequestDto.id()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 요청 단어"));

        // resolvedAt이 null이면 현재 시간 설정, 그렇지 않으면 null로 설정
        if (topicRequest.getResolvedAt() == null) {
            topicRequest.setResolvedAt(LocalDateTime.now().withNano(0));
        } else {
            topicRequest.setResolvedAt(null);
        }

        // 엔티티 저장
        topicRequestRepository.save(topicRequest);
        return ResponseEntity.ok(ApiResponseWrapper.success());
    }

    // 유저아바타 생성
    public ResponseEntity<ApiResponseWrapper> createUserAvatar(CreateUserAvatarRequestDto createUserAvatarRequestDto, CustomUserDetails userDetails) {
        try {
            UserAvatar userAvatar = UserAvatar.builder()
                    .avatar_img_path(awsS3Service.extractPathFromUrl(createUserAvatarRequestDto.s3ImgUrl()))
                    .createdBy(userDetails.getRole())
                    .build();
            userAvatarRepository.save(userAvatar);

            return ResponseEntity.ok(ApiResponseWrapper.success(new CreateUserAvatarResponseDto(userAvatar.getId())));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 유저 아바타 전체조회
    public ResponseEntity<ApiResponseWrapper> getUserAvatar() {
        List<UserAvatar> userAvatars = userAvatarRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        List<GetUserAvatarProcessDto> resultList = userAvatars.stream().map(userAvatar ->
                new GetUserAvatarProcessDto(
                        userAvatar.getId(),
                        awsS3Service.combineWithCloudFrontBaseUrl(userAvatar.getAvatar_img_path()),
                        userAvatar.getCreatedBy(),
                        userAvatar.getCreatedTime(),
                        userAvatar.getModifiedTime()
                )
        ).toList();

        AllUserAvatarResponseDto resultDto = new AllUserAvatarResponseDto(resultList);
        return ResponseEntity.ok(ApiResponseWrapper.success(resultDto));
    }

    // 달력 이모티콘 생성
    public ResponseEntity<ApiResponseWrapper> createCalendarEmoticon(CreateCalendarEmoticonRequestDto createCalendarEmoticonRequestDto, CustomUserDetails userDetails) {
        try {
            CalendarEmoticon calendarEmoticon = CalendarEmoticon.builder()
                    .emoticon_img_path(awsS3Service.extractPathFromUrl(createCalendarEmoticonRequestDto.s3ImgUrl()))
                    .createdBy(userDetails.getRole())
                    .build();
            calendarEmoticonRepository.save(calendarEmoticon);

            return ResponseEntity.ok(ApiResponseWrapper.success(new CreateUserAvatarResponseDto(calendarEmoticon.getId())));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // 달력 이모티콘 조회
    public ResponseEntity<ApiResponseWrapper> getCalendarEmoticon() {
        List<CalendarEmoticon> calendarEmoticons = calendarEmoticonRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        List<GetCalendarEmoticonProcessDto> resultList = calendarEmoticons.stream().map(calendarEmoticon ->
                new GetCalendarEmoticonProcessDto(
                        calendarEmoticon.getId(),
                        awsS3Service.combineWithCloudFrontBaseUrl(calendarEmoticon.getEmoticon_img_path()),
                        calendarEmoticon.getCreatedBy(),
                        calendarEmoticon.getCreatedTime(),
                        calendarEmoticon.getModifiedTime()
                )
        ).toList();

        AllCalendarEmoticonResponseDto resultDto = new AllCalendarEmoticonResponseDto(resultList);
        return ResponseEntity.ok(ApiResponseWrapper.success(resultDto));
    }

    // 토픽 요약 gpt 내용 생성 및 반환
    public ResponseEntity<ApiResponseWrapper> createTopicSummaryGptContent(CreateTopicSummaryGptContentRequestDto createTopicSummaryGptContentRequestDto, CustomUserDetails userDetails) {
        try {
            Topic topic = topicRepository.findById(createTopicSummaryGptContentRequestDto.id()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 토픽"));
            String prompt = topic.getTitle() + "을 한 문장으로 요약해줘. \n" +
                    "아래 답변 형식을 꼭 지켜서 답변해줘. \n" +
                    "[답변형식]\n" +
                    "[요약] : ";
            log.info("prompt : " + prompt);
            String gptAnswer = gptService.returnGptAnswer(prompt);
            log.info("answer : " + gptAnswer);

            String prefix = "[요약] : ";

            // '[요약] :' 문자열 뒤의 내용을 추출
            int summaryStart = gptAnswer.indexOf(prefix);

            if (summaryStart != -1) {
                String summary = gptAnswer.substring(summaryStart + prefix.length()).trim();
                return ResponseEntity.ok(ApiResponseWrapper.success(new GptResponseDto(summary)));
            } else {
                return ResponseEntity.internalServerError().body(ApiResponseWrapper.fail("GPT 답변 파싱 실패", gptAnswer));
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    // GPT 컬럼 내용 생성
    public ResponseEntity<ApiResponseWrapper> createGptColumnContent(CreateGptColumnRequestDto createGptColumnRequestDto) {
        String prompt = createGptColumnRequestDto.subject() + "에 대해서 한 페이지 정도 분량의 저널을 작성해줘. \n" +
                "너가 가진 금융지식을 이용해서 분석적으로 작성해줘. \n" +
                "소제목은 굵게 처리해주면 좋겠어 \n" +
                "답변 형식은 html 코드 형식으로 반환해줘" ;
        log.info("prompt : " + prompt);
        String gptAnswer = gptService.returnGptAnswer(prompt);
        log.info("answer : " + gptAnswer);
        // 맨 앞의 "\\html" 문자열 제거
        gptAnswer = gptAnswer.substring(7);

        // 맨 뒤의 "\\" 문자 제거
        gptAnswer = gptAnswer.substring(0, gptAnswer.length() - 3);
        return ResponseEntity.ok(ApiResponseWrapper.success(new GptResponseDto(gptAnswer)));
    }

    // GPT 컬럼 요약 생성
    public ResponseEntity<ApiResponseWrapper> createGptColumnSummary(CreateGptColumnRequestDto createGptColumnRequestDto) {
        String prompt = createGptColumnRequestDto.subject() + "을 한 문장으로 요약해줘. \n" +
                "아래 답변 형식을 꼭 지켜서 답변해줘. \n" +
                "[답변형식]\n" +
                "[요약] : ";
        log.info("prompt : " + prompt);
        String gptAnswer = gptService.returnGptAnswer(prompt);
        log.info("answer : " + gptAnswer);

        String prefix = "[요약] : ";

        // '[요약] :' 문자열 뒤의 내용을 추출
        int summaryStart = gptAnswer.indexOf(prefix);

        if (summaryStart != -1) {
            String summary = gptAnswer.substring(summaryStart + prefix.length()).trim();
            return ResponseEntity.ok(ApiResponseWrapper.success(new GptResponseDto(summary)));
        } else {
            return ResponseEntity.internalServerError().body(ApiResponseWrapper.fail("GPT 답변 파싱 실패", gptAnswer));
        }
    }

    // GPT 컬럼 저장
    public ResponseEntity<ApiResponseWrapper> createGptColumn(SaveGptColumnRequestDto saveGptColumnRequestDto, CustomUserDetails userDetails) {
        try {
            List<Long> topicList = saveGptColumnRequestDto.topicList();
            // 모든 토픽이 존재하는지 확인
            if (!topicList.isEmpty()) {
                for (Long topicId : topicList) {
                    Topic topic = topicRepository.findById(topicId).orElseThrow(EntityNotFoundException::new);
                }
            }
            GptColumn gptColumn = GptColumn.builder()
                    .title(saveGptColumnRequestDto.title())
                    .summary(saveGptColumnRequestDto.summary())
                    .content(saveGptColumnRequestDto.content())
                    .backgroundUrl(awsS3Service.extractPathFromUrl(saveGptColumnRequestDto.backgroundUrl()))
                    .useYN(saveGptColumnRequestDto.useYN())
                    .createdBy(userDetails.getRole())
                    .build();
            gptColumnRepository.save(gptColumn);

            for (Long topicId : topicList) {
                Topic topic = topicRepository.findById(topicId).get();

                TopicGptColumn topicGptColumn = TopicGptColumn.builder()
                        .topic(topic)
                        .gptColumn(gptColumn)
                        .build();

                topicGptColumnRepository.save(topicGptColumn);
                gptColumn.addTopicGptColumn(topicGptColumn);
            }

            return ResponseEntity.ok(ApiResponseWrapper.success(new CreateTopicResponseDto(gptColumn.getId())));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }


    // GPT 컬럼 전체 조회
    public ResponseEntity<ApiResponseWrapper> getGptColumn() {
        List<GptColumn> gptColumns = gptColumnRepository.findAll(Sort.by(Sort.Direction.DESC, "id"));
        List<GetGptColumnProcessDto> resultList = gptColumns.stream().map(gptColumn ->
                new GetGptColumnProcessDto(
                        gptColumn.getId(),
                        gptColumn.getCreatedBy(),
                        gptColumn.getCreatedTime(),
                        gptColumn.getModifiedTime(),
                        gptColumn.getTitle(),
                        gptColumn.getUseYN()
                )
        ).toList();

        AllGptColumnResponseDto resultDto = new AllGptColumnResponseDto(resultList);
        return ResponseEntity.ok(ApiResponseWrapper.success(resultDto));
    }

    /**
     * GPT 컬럼의 상세 내용(댓글, 댓글신고여부)을 조회한다.
     * @Return DetailGptColumnResponseDto
     * */
    public ResponseEntity<ApiResponseWrapper> getDetailGptColumn(Long id) {
        GptColumn gptColumn = gptColumnRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("존재 하지 않는 GPT COLUMN"));
        List<Comments> comments = commentsRepository.findByGptColumn(gptColumn);
        List<AdminCommentResponseDto> commentList = comments.stream()
                .map(comment -> {
                    Member member = comment.getMember();
                    String avatarPath = Optional.ofNullable(member.getUserAvatar())
                            .map(UserAvatar::getAvatar_img_path)
                            .map(awsS3Service::combineWithCloudFrontBaseUrl)
                            .orElse(null); // getUserAvatar()가 null이면 null 반환
                    if (commentsReportRepository.existsByReportedComment(comment)) {
                        return new AdminCommentResponseDto(member, comment, avatarPath, "Y");
                    } else {
                        return new AdminCommentResponseDto(member, comment, avatarPath, "N");
                    }
                }).toList();
        DetailGptColumnResponseDto detailGptColumnResponseDto = new DetailGptColumnResponseDto(
               gptColumn, awsS3Service.combineWithCloudFrontBaseUrl(gptColumn.getBackgroundUrl()), commentList);

        return ResponseEntity.ok(ApiResponseWrapper.success(detailGptColumnResponseDto));
    }

    // GPT COLUMN 수정
    public ResponseEntity<ApiResponseWrapper> modifyGptColumn(ModifyGptColumnRequestDto modifyGptColumnRequestDto, CustomUserDetails userDetails) {
        try {
            GptColumn gptColumn = gptColumnRepository.findById(modifyGptColumnRequestDto.getId()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 gpt column"));
            // 모든 토픽이 존재하는지 확인
            List<Long> topicList = modifyGptColumnRequestDto.getTopicList();
            if (!topicList.isEmpty()) {
                for (Long topicId : topicList) {
                    Topic topic = topicRepository.findById(topicId).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 토픽: " + topicId));
                }
            }

            // 기존 TopicGptColumn 삭제
            topicGptColumnRepository.deleteAllByGptColumnId(gptColumn.getId());
            // 새로운 TopicGptColumn 생성 후 quiz에 저장
            for (Long topicId : topicList) {
                Topic topic = topicRepository.findById(topicId).get();

                TopicGptColumn topicGptColumn = TopicGptColumn.builder()
                        .topic(topic)
                        .gptColumn(gptColumn)
                        .build();

                topicGptColumnRepository.save(topicGptColumn);
                // gpt column 토픽 리스트 수정
                gptColumn.addTopicGptColumn(topicGptColumn);
            }
            // gpt column 나머지 데이터 수정
            gptColumn.modifyGptColumn(modifyGptColumnRequestDto, awsS3Service.extractPathFromUrl(modifyGptColumnRequestDto.getBackgroundUrl()), userDetails.getRole());

            return ResponseEntity.ok(ApiResponseWrapper.success());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    // 공지사항 생성 api
    public ResponseEntity<ApiResponseWrapper> createAnnouncement(CustomUserDetails userDetails, CreateAnnounceRequestDto dto) throws JsonProcessingException {
        announcementRepository.save(new Announcement(dto.getTitle(), dto.getContent(), userDetails.getRole()));
        CreateFcmMessageRequestDto newRequest = CreateFcmMessageRequestDto.builder()
                .type(4L)
                .target(new ArrayList<>())
                .title("새로운 공지사항이 등록되었습니다.")
                .content("클릭하여 확인하세요.")
                .view("https://api.fin-hub.co.kr/api/v1/main/announce")
                .action(FcmMessageProcessDto.Action.builder().date(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))).build())
                .build();

        fcmService.sendFcmNotifications(newRequest);
        return  ResponseEntity.ok(ApiResponseWrapper.success());
    }

    // 공지사항 수정 api
    public ResponseEntity<ApiResponseWrapper> updateAnnouncement(CustomUserDetails userDetails, ModifyAnnounceRequestDto dto) {

        Announcement originAnnounce = announcementRepository.findById(dto.getId())
                .orElseThrow(() -> new EntityNotFoundException("공지사항이 존재하지 않습니다."));

        originAnnounce.updateAnnounce(dto);
        announcementRepository.save(originAnnounce);
        return ResponseEntity.ok(ApiResponseWrapper.success());
    }
    // 공지사항 삭제 api
    public ResponseEntity<ApiResponseWrapper> deleteAnnouncement(CustomUserDetails userDetails, DeleteAnnounceRequestDto dto){

        Announcement originAnnounce = announcementRepository.findById(dto.getId())
                .orElseThrow(() -> new EntityNotFoundException("공지사항이 존재하지 않습니다."));
        announcementRepository.delete(originAnnounce);
        return ResponseEntity.ok(ApiResponseWrapper.success());
    }

    // 신고사유 조회
    public ResponseEntity<ApiResponseWrapper> commentReasons() {
        List<ReportReasons> reportReasons = reportReasonsRepository.findAllByOrderByIdAsc();
        List<ReportReasonsDto> reasonList = reportReasons.stream().map(reason -> {
            return new ReportReasonsDto(reason);
        }).toList();

        return ResponseEntity.ok(ApiResponseWrapper.success(new ReportReasonsAnswerDto(reasonList)));
    }

    // 신고사유 등록
    public ResponseEntity<ApiResponseWrapper> registerReportReason(ReportReasonRequestDto dto) {
        Optional<ReportReasons> findReason = reportReasonsRepository.findByReason(dto.reason());
        if (findReason.isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseWrapper.fail("이미 존재하는 신고사유"));
        }
        ReportReasons reportReasons = ReportReasons.builder()
                .reason(dto.reason())
                .build();
        reportReasonsRepository.save(reportReasons);
        return ResponseEntity.ok(ApiResponseWrapper.success());
    }

    // 신고사유 수정
    public ResponseEntity<ApiResponseWrapper> modifyReportReason(ReportReasonModifyRequestDto dto) {
        ReportReasons reportReasons = reportReasonsRepository.findById(dto.id()).orElseThrow(() -> new EntityNotFoundException("신고이유ID가 존재하지 않습니다."));
        Optional<ReportReasons> findReason = reportReasonsRepository.findByReason(dto.reason());
        if (findReason.isPresent() && !dto.id().equals(findReason.get().getId())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponseWrapper.fail("이미 존재하는 신고사유"));
        }
        reportReasons.modifyReportReasons(dto);
        reportReasonsRepository.save(reportReasons);
        return ResponseEntity.ok(ApiResponseWrapper.success());
    }

    public ResponseEntity<ApiResponseWrapper> saveFcmToken(CustomUserDetails userDetails, SaveFcmTokenRequestDto dto){
        Long managerId = userDetails.getMemberIdasLong();
        Manager manager = managerRepository.findById(managerId).orElseThrow(() -> new EntityNotFoundException("관리자 id 존재하지 않습니다."));
        manager.updateFcmToken(dto.getToken());
        managerRepository.save(manager);
        return ResponseEntity.ok(ApiResponseWrapper.success());
    }

    // 공지사항 조회
    public ResponseEntity<ApiResponseWrapper> getAnnounceList(int page, int pageSize){
        PageRequest pageable = PageRequest.of(page - 1, pageSize);
        Page<Announcement> announceList = announcementRepository.findOrderByTime(pageable);
        List<AnnouncementProcessDto> announcementProcessDto = announceList.getContent().stream().map(
                announcement -> AnnouncementProcessDto.builder()
                        .id(announcement.getId())
                        .title(announcement.getTitle())
                        .content(announcement.getContent())
                        .time(announcement.getCreatedTime())
                        .build())
        .collect(Collectors.toList());
        PageInfoProcessDto pageInfoProcessDto =
                new PageInfoProcessDto(announceList.getNumber()+1, announceList.getTotalPages(), announceList.getSize(), announceList.getTotalElements());
        return ResponseEntity.ok(ApiResponseWrapper.success(new AnnouncementResponseDto(announcementProcessDto, pageInfoProcessDto)));
    }

    public ResponseEntity<ApiResponseWrapper> adminAutoLogin(HttpServletRequest request){
        String accessTokenHeader = request.getHeader("Authorization");
        String refreshToken = request.getHeader("refreshToken");
        String accessToken = null;
        if (accessTokenHeader != null && accessTokenHeader.startsWith("Bearer ")) {
            accessToken = accessTokenHeader.substring(7); // "Bearer " 이후의 JWT만 추출
        }
        if(jwtUtil.validateTokenServiceLayer(accessToken)){
            // 액세스토큰 유효할 때
            String uuid = jwtUtil.getUuid(accessToken);
            Manager manager = managerRepository.findByManagerUuid(uuid).orElseThrow(
                    () -> new EntityNotFoundException("MANAGER ID가 존재하지 않습니다."));
            AdminAutoLoginResponseDto adminAutoLoginResponseDto = updatingLoginResponse(manager);
            return ResponseEntity.ok(ApiResponseWrapper.success(adminAutoLoginResponseDto));
        }
        if (jwtUtil.validateTokenServiceLayer(refreshToken)) {
            // 액세스토큰 유효x, 리프레시토큰 유효할 때
            String uuid = jwtUtil.getUuid(refreshToken);
            Manager manager = managerRepository.findByManagerUuid(uuid).orElseThrow(
                    () -> new EntityNotFoundException("MEMBER ID가 존재하지 않습니다."));
            AdminAutoLoginResponseDto adminAutoLoginResponseDto = updatingLoginResponse(manager);
            return ResponseEntity.ok(ApiResponseWrapper.success(adminAutoLoginResponseDto));
        }
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponseWrapper.fail("로그인이 필요합니다"));
    }

    public AdminAutoLoginResponseDto updatingLoginResponse(Manager manager){
        TokenDto allTokens = jwtUtil.createAllTokens(manager.getManagerUuid(), manager.getRole().toString(), "admin");
        Optional<ManagerRefreshToken> existingRefreshToken = managerRefreshRepository.findByEmail(manager.getEmail());
        if (existingRefreshToken.isPresent()) {
            // 기존 리프레시 토큰 정보가 있는 경우, 새 리프레시 토큰으로 업데이트
            ManagerRefreshToken managerRefreshToken = existingRefreshToken.get();
            managerRefreshToken.updateToken(allTokens.getRefreshToken());
            managerRefreshRepository.save(managerRefreshToken);
        } else {
            // 기존 리프레시 토큰 정보가 없는 경우, 새로운 리프레시 토큰 저장
            managerRefreshRepository.save(new ManagerRefreshToken(allTokens.getRefreshToken(), manager.getEmail()));
        }
        // 응답 데이터 : 이름, 이메일, 등급
        AdminAutoLoginProcessDto dto = AdminAutoLoginProcessDto.builder()
                .role(manager.getRole())
                .email(manager.getEmail()).build();
        return new AdminAutoLoginResponseDto(allTokens, dto);
    }

    public ResponseEntity<ApiResponseWrapper> getAnnounceDetail(Long id){
        Announcement announcement = announcementRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("공지사항 id가 존재하지 않습니다."));
        DetailAnnounceResponseDto detail = DetailAnnounceResponseDto.builder()
                .id(announcement.getId())
                .title(announcement.getTitle())
                .content(announcement.getContent()).build();
        return ResponseEntity.ok(ApiResponseWrapper.success(detail));
    }

    // 신고된 댓글 보기
    public ResponseEntity<ApiResponseWrapper> getReportedComment(Pageable pageable, String useYN) {
        Page<CommentsReport> commentsReports = commentsReportRepositoryCustom.searchAllTCommentsReportFilterList(pageable, useYN);
        List<ReportedCommentsProcessDto> reportedCommentsProcessDtoList = commentsReports.getContent().stream().map(ReportedCommentsProcessDto::new).toList();
        PageInfoProcessDto PageInfoProcessDto = commonService.setPageInfo(commentsReports);
        ReportedCommentsResponseDto allTopicRequestResponseDto = new ReportedCommentsResponseDto(reportedCommentsProcessDtoList, PageInfoProcessDto);

        return ResponseEntity.ok(ApiResponseWrapper.success(allTopicRequestResponseDto));
    }

    // 신고된 댓글 처리
    public ResponseEntity<ApiResponseWrapper> postReportedComment(ReportCommentRequestDto dto) {
        Comments comment = commentsRepository.findById(dto.id()).orElseThrow(() -> new EntityNotFoundException("신고된 comments ID가 없습니다."));
        CommentsReport commentsReport = commentsReportRepository.findByReportedComment(comment).orElseThrow(() -> new EntityNotFoundException("신고된 commentsReport ID가 없습니다."));
        comment.modifyUseYn(); // 무조건 n으로
        commentsReport.modifyUseYn(); // 무조건 n으로
        return ResponseEntity.ok(ApiResponseWrapper.success());
    }

    // 카테고리, 토픽 순서 지정
    public ResponseEntity<ApiResponseWrapper> order(OrderRequestDto dto, String type) {
        try {
            if ("category".equals(type)) {
                for (Map.Entry<Long, Long> order : dto.orders().entrySet()) {
                    categoryRepository.updatePosition(order.getKey(), order.getValue());
                }
            } else if ("topic".equals(type)) {
                for (Map.Entry<Long, Long> order : dto.orders().entrySet()) {
                    topicRepository.updatePosition(order.getKey(), order.getValue());
                }
            }
            return ResponseEntity.ok(ApiResponseWrapper.success());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ApiResponseWrapper.fail("알 수 없는 오류 발생"));
        }
    }

    public ResponseEntity<ApiResponseWrapper> vocList(int page, int size, String reply){
        Pageable pageable = PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "createdTime"));
        Page<Feedback> feedbackList;
        if(reply != null){
            feedbackList = feedbackRepository.findFeedbacksByReply(reply, pageable);
        }
        else{
            feedbackList = feedbackRepository.findAll(pageable);
        }
        List<VocListProcessDto> vocDetailProcessDtoList = feedbackList.stream().map(feedback -> VocListProcessDto.builder()
                .feedbackId(feedback.getId())
                .email(feedback.getEmail())
                .context(feedback.getFeedback())
                .reply(feedback.getReply())
                .createdTime(feedback.getCreatedTime()).build()).toList();

        VocListPageInfoProcessDto pageInfo = VocListPageInfoProcessDto.builder()
                .currentPage(page)
                .totalPages(feedbackList.getTotalPages())
                .pageSize(size)
                .totalElements(feedbackList.getTotalElements())
                .build();
        return ResponseEntity.ok(ApiResponseWrapper.success(new VocListResponseDto(vocDetailProcessDtoList, pageInfo)));
    }

    public ResponseEntity<ApiResponseWrapper> vocDetail(Long id){
        Feedback feedback = feedbackRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 VOC"));
        VocDetailProcessDto vocDetailProcessDto = VocDetailProcessDto.builder()
                .feedbackId(feedback.getId())
                .userAgent(feedback.getUserAgent())
                .appVersion(feedback.getAppVersion())
                .email(feedback.getEmail())
                .context(feedback.getFeedback())
                .fileUrl1(checkIfNullFileUrl(feedback.getFileUrl1()))
                .fileUrl2(checkIfNullFileUrl(feedback.getFileUrl2()))
                .fileUrl3(checkIfNullFileUrl(feedback.getFileUrl3()))
                .fileUrl4(checkIfNullFileUrl(feedback.getFileUrl4()))
                .adminResponse(feedback.getAdminResponse())
                .reply(feedback.getReply())
                .build();
        return ResponseEntity.ok(ApiResponseWrapper.success(new VocDetailResponseDto(vocDetailProcessDto)));
    }

    private String checkIfNullFileUrl(String fileUrl) {
        return Optional.ofNullable(fileUrl)
                .map(awsS3Service::combineWithCloudFrontBaseUrl)
                .orElse(null);
    }

    public ResponseEntity<ApiResponseWrapper> sendReply(ReplyRequestDto dto) {
        Feedback feedback = feedbackRepository.findById(dto.id()).orElseThrow(() -> new EntityNotFoundException("존재하지 않는 VOC"));
        try {
            feedback.updateFeedback("T", dto.text());
            emailService.sendReplyEmail(feedback.getEmail(), feedback.getFeedback(), dto.text());
        } catch (MessagingException exception){
            return ResponseEntity.badRequest().body(ApiResponseWrapper.fail("메일 전송 실패"));
        }
        return ResponseEntity.ok(ApiResponseWrapper.success());
    }
}
