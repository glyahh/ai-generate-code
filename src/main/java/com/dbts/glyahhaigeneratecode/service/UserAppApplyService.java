package com.dbts.glyahhaigeneratecode.service;

import com.dbts.glyahhaigeneratecode.model.Entity.User;
import com.dbts.glyahhaigeneratecode.model.Entity.UserAppApply;
import com.dbts.glyahhaigeneratecode.model.VO.ApplyHistoryVO;
import com.dbts.glyahhaigeneratecode.model.VO.ApplyVO;
import com.mybatisflex.core.service.IService;

import java.util.List;

/**
 * 用户应用 / 权限申请记录 服务层。
 *
 * @author <a href="https://github.com/glyahh">glyahh</a>
 */
public interface UserAppApplyService extends IService<UserAppApply> {

    /**
     * 创建一条用户申请记录。
     *
     * 权限校验：
     * - 如果当前用户已经是管理员，则直接返回 false，并由上层返回对应提示
     * - 根据 operate 判断是申请精选应用还是申请管理员
     *
     * @param appId        申请关联的应用 id（申请管理员时可为空）
     * @param appPropriety 申请的应用展示优先级（仅在申请精选应用时使用）
     * @param operate      操作类型：1-申请将自己的应用设置为精选应用；2-申请成为管理员
     * @param applyReason  申请理由
     * @param loginUser    当前登录用户
     * @return 申请记录是否创建成功
     */
    boolean createUserAppApply(Long appId, Integer appPropriety, Integer operate, String applyReason, User loginUser);

    /**
     * 【管理员】回显待处理申请（status=0）
     *
     * @param loginUser 当前登录用户
     * @return 待处理申请列表
     */
    List<ApplyVO> listPendingApplyVO(User loginUser);

    /**
     * 【用户】查看自己的申请历史记录（含处理状态与审核备注）
     *
     * @param loginUser 当前登录用户（必须是普通用户）
     * @return 申请历史列表
     */
    List<ApplyHistoryVO> listMyApplyHistoryVO(User loginUser);

    /**
     * 【管理员】同意某条申请，并同步更新对应业务数据
     *
     * @param applyId   申请记录 id
     * @param loginUser 当前登录管理员
     * @return 是否处理成功
     */
    boolean agreeApply(Long applyId, User loginUser);

    /**
     * 【管理员】拒绝某条申请，仅更新申请记录状态及审核备注
     *
     * @param applyId      申请记录 id
     * @param reviewRemark 审核备注（拒绝理由）
     * @param applyReason  管理员修改后的申请理由（可选，若为空则不修改原申请理由）
     * @param loginUser    当前登录管理员
     * @return 是否处理成功
     */
    boolean rejectApply(Long applyId, String reviewRemark, String applyReason, User loginUser);
}

