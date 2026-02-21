/* eslint-disable */
// @ts-ignore

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
  data?: number;
  message?: string;
};

export type BaseResponseObject = {
  code?: number;
  data?: Record<string, unknown>;
  message?: string;
};

export type BaseResponsePageUserVO = {
  code?: number;
  data?: PageUserVO;
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

export type DeleteRequest = {
  id?: number;
};

export type LoginUserVO = {
  id?: number;
  userAccount?: string;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
  createTime?: string;
  updateTime?: string;
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

export type TestUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseObject;
};

export type User = {
  id?: number;
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
  id: number;
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
  id: number;
};

export type UserGetUsingGetResponses = {
  /**
   * OK
   */
  200: BaseResponseUser;
};

export type UserGetVoUsingGetParams = {
  id: number;
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
  id?: number;
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
  id: number;
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
  id?: number;
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

export type UserVO = {
  id?: number;
  userAccount?: string;
  userName?: string;
  userAvatar?: string;
  userProfile?: string;
  userRole?: string;
  createTime?: string;
};
