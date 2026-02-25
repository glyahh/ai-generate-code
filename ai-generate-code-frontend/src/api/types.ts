/* eslint-disable */
// @ts-ignore

export type AppAddRequest = {
  appName?: string;
  cover?: string;
  initPrompt?: string;
  codeGenType?: string;
};

export type AppAddUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseLong;
};

export type AppAdminGetVoUsingGetParams = {
  id: string | number;
};

export type AppAdminGetVoUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseAppVO;
};

export type AppAdminListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponsePageAppVO;
};

export type AppAdminOpenApiDeleteUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppAdminUpdateRequest = {
  id?: string | number;
  appName?: string;
  cover?: string;
  priority?: number;
};

export type AppAdminUpdateUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppDeployRequest = {
  appId?: string | number;
};

export type AppDeployUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseString;
};

export type AppGetVoUsingGetParams = {
  id: string | number;
};

export type AppGetVoUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseAppVO;
};

export type AppGoodListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponsePageAppVO;
};

export type AppMyListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponsePageAppVO;
};

export type AppOpenApiDeleteUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppQueryRequest = {
  pageNum?: number;
  pageSize?: number;
  sortField?: string;
  sortOrder?: string;
  id?: string | number;
  appName?: string;
  cover?: string;
  initPrompt?: string;
  codeGenType?: string;
  deployKey?: string;
  priority?: number;
  userId?: string | number;
  isDelete?: number;
};

export type AppUpdateRequest = {
  id?: string | number;
  appName?: string;
};

export type AppUpdateUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type AppVO = {
  id?: string | number;
  appName?: string;
  cover?: string;
  initPrompt?: string;
  codeGenType?: string;
  deployKey?: string;
  deployedTime?: string;
  priority?: number;
  userId?: string | number;
  editTime?: string;
  createTime?: string;
  updateTime?: string;
  userVO?: UserVO;
  /** 是否已有生成代码（存在预览） */
  hasGeneratedCode?: boolean;
};

export type BaseResponseAppVO = {
  code?: number;
  data?: AppVO;
  message?: string;
};

export type BaseResponseBoolean = {
  code?: number;
  data?: boolean;
  message?: string;
};

export type BaseResponseLoginUserVO = {
  code?: number;
  data?: LoginUserVO;
  message?: string;
};

export type BaseResponseLong = {
  code?: number;
  data?: string | number;
  message?: string;
};

export type BaseResponseObject = {
  code?: number;
  data?: Record<string, unknown>;
  message?: string;
};

export type BaseResponsePageAppVO = {
  code?: number;
  data?: PageAppVO;
  message?: string;
};

export type BaseResponsePageUserVO = {
  code?: number;
  data?: PageUserVO;
  message?: string;
};

export type BaseResponseString = {
  code?: number;
  data?: string;
  message?: string;
};

export type BaseResponseUser = {
  code?: number;
  data?: User;
  message?: string;
};

export type BaseResponseUserVO = {
  code?: number;
  data?: UserVO;
  message?: string;
};

export type ChatGenCodeUsingGetParams = {
  appId: string | number;
  message: string;
};

export type ChatGenCodeUsingGetResponses = {
  /**
   * OK
   */
  200: ServerSentEventString[];
};

export type DeleteRequest = {
  id?: string | number;
};

export type LoginUserVO = {
  id?: string | number;
  userAccount?: string;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
  createTime?: string;
  updateTime?: string;
};

export type PageAppVO = {
  records?: AppVO[];
  pageNumber?: number;
  pageSize?: number;
  totalPage?: number;
  totalRow?: number;
  optimizeCountQuery?: boolean;
};

export type PageUser = {
  records?: User[];
  pageNumber?: number;
  pageSize?: number;
  totalPage?: number;
  totalRow?: number;
  optimizeCountQuery?: boolean;
};

export type PageUserVO = {
  records?: UserVO[];
  pageNumber?: number;
  pageSize?: number;
  totalPage?: number;
  totalRow?: number;
  optimizeCountQuery?: boolean;
};

export type ServerSentEventString = object;

export type StaticDeployKeyUsingGetParams = {
  deployKey: string;
};

export type StaticDeployKeyUsingGetResponses = {
  /**
   * OK
   */
  200: string;
};

export type TestUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseObject;
};

export type User = {
  id?: string | number;
  userAccount?: string;
  userPassword?: string;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
  editTime?: string;
  createTime?: string;
  updateTime?: string;
  isDelete?: number;
};

export type UserAddRequest = {
  userName?: string;
  userAccount?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
};

export type UserAddUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseLong;
};

export type UserGetInfoIdUsingGetParams = {
  id: string | number;
};

export type UserGetInfoIdUsingGetResponses = {
  /**
   * OK
   */
  200: User;
};

export type UserGetLoginUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseLoginUserVO;
};

export type UserGetUsingGetParams = {
  id: string | number;
};

export type UserGetUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseUser;
};

export type UserGetVoUsingGetParams = {
  id: string | number;
};

export type UserGetVoUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseUserVO;
};

export type UserListPageVoUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponsePageUserVO;
};

export type UserListUsingGetResponses = {
  /**
   * OK
   */
  200: User[];
};

export type UserLoginRequest = {
  userAccount?: string;
  userPassword?: string;
};

export type UserLoginUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseLoginUserVO;
};

export type UserLogoutUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type UserOpenApiDeleteUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type UserPageUsingGetParams = {
  page: PageUser;
};

export type UserPageUsingGetResponses = {
  /**
   * OK
   */
  200: PageUser;
};

export type UserQueryRequest = {
  pageNum?: number;
  pageSize?: number;
  sortField?: string;
  sortOrder?: string;
  id?: string | number;
  userName?: string;
  userAccount?: string;
  userProfile?: string;
  userRole?: string;
};

export type UserRegisterRequest = {
  userAccount?: string;
  userPassword?: string;
  checkPassword?: string;
};

export type UserRegisterUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseLong;
};

export type UserRemoveIdUsingDeleteParams = {
  id: string | number;
};

export type UserRemoveIdUsingDeleteResponses = {
  /**
   * OK
   */
  200: boolean;
};

export type UserSaveUsingPostResponses = {
  /**
   * OK
   */
  200: boolean;
};

export type UserUpdateRequest = {
  id?: string | number;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
};

export type UserUpdateUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type UserUpdateUsingPutResponses = {
  /**
   * OK
   */
  200: boolean;
};

export type UserUpdatePasswordRequest = {
  oldPassword?: string;
  newPassword?: string;
  checkPassword?: string;
};

export type UserUpdatePasswordUsingPostResponses = {
  /**
   * OK
   */
  200: BaseResponseBoolean;
};

export type UserVO = {
  id?: string | number;
  userAccount?: string;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
  createTime?: string;
};
