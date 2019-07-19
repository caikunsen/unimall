package com.iotechn.unimall.app.api.user;

import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.mapper.EntityWrapper;
import com.iotechn.unimall.core.exception.ExceptionDefinition;
import com.iotechn.unimall.core.exception.AppServiceException;
import com.iotechn.unimall.core.Const;
import com.iotechn.unimall.core.exception.ServiceException;
import com.iotechn.unimall.core.notify.SMSClient;
import com.iotechn.unimall.core.notify.SMSResult;
import com.iotechn.unimall.core.util.GeneratorUtil;
import com.iotechn.unimall.data.component.CacheComponent;
import com.iotechn.unimall.data.domain.UserDO;
import com.iotechn.unimall.data.dto.UserDTO;
import com.iotechn.unimall.data.enums.UserLoginType;
import com.iotechn.unimall.data.mapper.UserMapper;
import com.iotechn.unimall.data.util.SessionUtil;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.apache.commons.codec.digest.Md5Crypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Date;
import java.util.List;

/**
 * Created by rize on 2019/6/30.
 */
@Service
public class UserServiceImpl implements UserService {

    private static final String VERIFY_CODE_PREFIX = "VERIFY_CODE_";

    private static final Logger logger = LoggerFactory.getLogger(UserServiceImpl.class);

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private SMSClient smsClient;

    @Autowired
    private CacheComponent cacheComponent;

    @Autowired
    private StringRedisTemplate userRedisTemplate;

    private OkHttpClient okHttpClient = new OkHttpClient();

    @Value("${com.iotechn.unimall.wx.mini.app-id}")
    private String wxMiNiAppid;

    @Value("${com.iotechn.unimall.wx.mini.app-secret}")
    private String wxMiNiSecret;

    @Value("${com.iotechn.unimall.wx.app.app-id}")
    private String wxAppAppid;

    @Value("${com.iotechn.unimall.wx.app.app-secret}")
    private String wxAppSecret;

    @Override
    public String sendVerifyCode(String phone) throws ServiceException {
        String verifyCode = GeneratorUtil.genSixVerifyCode();
        SMSResult res = smsClient.sendRegisterVerify(phone, verifyCode);
        if (res.isSucc()) {
            cacheComponent.putRaw(VERIFY_CODE_PREFIX + phone, verifyCode, 300);
            return "ok";
        } else {
            throw new AppServiceException(res.getMsg(), ExceptionDefinition.USER_SEND_VERIFY_FAILED.getCode());
        }

    }

    @Override
    @Transactional
    public String register(String phone, String password, String verifyCode, String ip) throws ServiceException {
        //1.校验验证码
        checkVerifyCode(phone, verifyCode);
        //2.校验用户是否存在
        Integer count = userMapper.selectCount(
                new EntityWrapper<UserDO>()
                        .eq("phone", phone));
        if (count > 0) {
            throw new AppServiceException(ExceptionDefinition.USER_PHONE_HAS_EXISTED);
        }
        //3.校验成功，注册用户
        Date now = new Date();
        UserDO userDO = new UserDO();
        userDO.setPhone(phone);
        userDO.setPassword(Md5Crypt.md5Crypt(password.getBytes(), "$1$" + phone.substring(0, 7)));
        userDO.setLastLoginIp(ip);
        userDO.setGmtLastLogin(now);
        userDO.setGmtUpdate(now);
        userDO.setGmtCreate(now);
        userDO.setLoginType(UserLoginType.REGISTER.getCode());
        userMapper.insert(userDO);
        //返回用户DTO
        cacheComponent.del(VERIFY_CODE_PREFIX + phone);
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String bindPhone(String phone, String password, String verifyCode, Long userId) throws ServiceException {
        //1.校验验证码
        checkVerifyCode(phone, verifyCode);
        //2.校验用户是否存在
        Integer count = userMapper.selectCount(
                new EntityWrapper<UserDO>()
                        .eq("phone", phone));
        if (count > 0) {
            throw new AppServiceException(ExceptionDefinition.USER_PHONE_HAS_EXISTED);
        }
        //3.校验成功，绑定手机
        UserDO updateUserDO = new UserDO();
        updateUserDO.setId(userId);
        updateUserDO.setPhone(phone);
        updateUserDO.setGmtUpdate(new Date());
        if (userMapper.updateById(updateUserDO) > 0) {
            cacheComponent.del(VERIFY_CODE_PREFIX + phone);
            return "ok";
        }
        throw new AppServiceException(ExceptionDefinition.USER_UNKNOWN_EXCEPTION);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String resetPassword(String phone, String password, String verifyCode) throws ServiceException {
        //1.校验验证码
        checkVerifyCode(phone, verifyCode);
        //2.校验用户是否存在
        List<UserDO> targetUserList = userMapper.selectList(
                new EntityWrapper<UserDO>()
                        .eq("phone", phone));
        if (CollectionUtils.isEmpty(targetUserList)) {
            throw new AppServiceException(ExceptionDefinition.USER_PHONE_NOT_EXIST);
        }
        Long id = targetUserList.get(0).getId();
        //3.校验成功，重置密码
        UserDO updateUserDO = new UserDO();
        updateUserDO.setId(id);
        updateUserDO.setPassword(Md5Crypt.md5Crypt(password.getBytes(), "$1$" + phone.substring(0, 7)));
        updateUserDO.setGmtUpdate(new Date());
        if (userMapper.updateById(updateUserDO) > 0) {
            cacheComponent.del(VERIFY_CODE_PREFIX + phone);
            return "ok";
        }
        throw new AppServiceException(ExceptionDefinition.USER_UNKNOWN_EXCEPTION);
    }

    /**
     * 验证码抽取校验
     *
     * @param phone
     * @param verifyCode
     * @throws ServiceException
     */
    private void checkVerifyCode(String phone, String verifyCode) throws ServiceException {
        String raw = cacheComponent.getRaw(VERIFY_CODE_PREFIX + phone);
        if (StringUtils.isEmpty(raw)) {
            throw new AppServiceException(ExceptionDefinition.USER_VERIFY_CODE_NOT_EXIST);
        }
        if (!raw.equals(verifyCode)) {
            throw new AppServiceException(ExceptionDefinition.USER_VERIFY_CODE_NOT_CORRECT);
        }
    }

    @Override
    public UserDTO login(String phone, String password, String ip) throws ServiceException {
        String cryptPassword = Md5Crypt.md5Crypt(password.getBytes(), "$1$" + phone.substring(0, 7));
        UserDTO userDTO = userMapper.login(phone, cryptPassword);
        if (userDTO == null) {
            throw new AppServiceException(ExceptionDefinition.USER_PHONE_OR_PASSWORD_NOT_CORRECT);
        }
        //检查帐号是否已经冻结
        if (userDTO.getStatus() == 0) {
            throw new AppServiceException(ExceptionDefinition.USER_CAN_NOT_ACTICE);
        }
        String accessToken = GeneratorUtil.genSessionId();
        //放入SESSION专用Redis数据源中
        userRedisTemplate.opsForValue().set(Const.USER_REDIS_PREFIX + accessToken, JSONObject.toJSONString(userDTO));
        userDTO.setAccessToken(accessToken);
        UserDO userUpdateDO = new UserDO();
        userUpdateDO.setId(userDTO.getId());
        userUpdateDO.setGmtLastLogin(new Date());
        userUpdateDO.setLastLoginIp(ip);
        userMapper.updateById(userUpdateDO);
        return userDTO;
    }

    @Override
    public String logout(String accessToken, Long userId) throws ServiceException {
        userRedisTemplate.delete(accessToken);
        return "ok";
    }

    @Override
    public UserDTO thirdPartLogin(Integer loginType, String ip, String raw) throws ServiceException {
        try {
            if (UserLoginType.MP_WEIXIN.getCode() == loginType) {
                return wechatLogin(loginType, ip, raw);
            } else if (UserLoginType.APP_WEIXIN.getCode() == loginType) {
                //return wechatLogin(loginType, ip, raw);
                //UNI-APP 的 微信APP登录 APPSecret是保存在前端的。这点非常不安全。但是用了他的框架，也没有办法
                JSONObject jsonObject = JSONObject.parseObject(raw);
                JSONObject authResult = jsonObject.getJSONObject("authResult");
                String openid = authResult.getString("openid");
//                String openid = "osTQe6L_JOoJkrMO1vAT_peMivjA";
                List<UserDO> userDOS = userMapper.selectList(new EntityWrapper<UserDO>().eq("open_id", openid).eq("login_type", loginType));
                UserDO userDO;
                if (CollectionUtils.isEmpty(userDOS)) {
                    //创建新用户
                    Date now = new Date();
                    UserDO newUserDO = new UserDO();
                    newUserDO.setLoginType(loginType);
                    newUserDO.setOpenId(openid);
                    newUserDO.setLastLoginIp(ip);
                    newUserDO.setGmtLastLogin(now);
                    newUserDO.setGmtUpdate(now);
                    newUserDO.setGmtCreate(now);
                    userMapper.insert(newUserDO);
                    //这一步是为了封装上数据库上配置的默认值
                    userDO = userMapper.selectById(newUserDO.getId());
                } else {
                    userDO = userDOS.get(0);
                    UserDO userUpdateDO = new UserDO();
                    userUpdateDO.setId(userDO.getId());
                    userUpdateDO.setGmtLastLogin(new Date());
                    userUpdateDO.setLastLoginIp(ip);
                    userMapper.updateById(userUpdateDO);
                }
                //检查帐号是否已经冻结
                if (userDO.getStatus() == 0) {
                    throw new AppServiceException(ExceptionDefinition.USER_CAN_NOT_ACTICE);
                }
                String accessToken = GeneratorUtil.genSessionId();
                UserDTO userDTO = new UserDTO();
                BeanUtils.copyProperties(userDO, userDTO);
                userRedisTemplate.opsForValue().set(Const.USER_REDIS_PREFIX + accessToken, JSONObject.toJSONString(userDTO));
                userDTO.setAccessToken(accessToken);
                return userDTO;
            } else {
                throw new AppServiceException(ExceptionDefinition.USER_THIRD_PART_NOT_SUPPORT);
            }
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            logger.error("[用户第三方登录] 异常", e);
            throw new AppServiceException(ExceptionDefinition.USER_THIRD_PART_LOGIN_FAILED);
        }
    }

    /**
     * 微信第三方登录 抽取接口
     * @param loginType
     * @param ip
     * @param raw
     * @return
     * @throws Exception
     */
    private UserDTO wechatLogin(Integer loginType, String ip, String raw) throws Exception {
        //微信第三方登录
        JSONObject thirdPartJsonObject = JSONObject.parseObject(raw);
        String code = thirdPartJsonObject.getString("code");
        String body = okHttpClient.newCall(new Request.Builder()
                .url("https://api.weixin.qq.com/sns/jscode2session?appid=" + (UserLoginType.MP_WEIXIN.getCode() == loginType ? wxMiNiAppid : wxAppAppid) +
                        "&secret=" + (UserLoginType.MP_WEIXIN.getCode() == loginType ? wxMiNiSecret : wxAppSecret) +
                        "&grant_type=authorization_code&js_code=" + code).get().build()).execute().body().string();
        JSONObject jsonObject = JSONObject.parseObject(body);
        Integer errcode = jsonObject.getInteger("errcode");
        if (errcode == null || errcode == 0) {
            String miniOpenId = jsonObject.getString("openid");
            List<UserDO> userDOS = userMapper.selectList(new EntityWrapper<UserDO>().eq("open_id", miniOpenId).eq("login_type", loginType));
            UserDO userDO;
            if (CollectionUtils.isEmpty(userDOS)) {
                //若用户为空，则注册此用户
                Date now = new Date();
                UserDO newUserDO = new UserDO();
                newUserDO.setLoginType(loginType);
                newUserDO.setOpenId(miniOpenId);
                newUserDO.setLastLoginIp(ip);
                newUserDO.setGmtLastLogin(now);
                newUserDO.setGmtUpdate(now);
                newUserDO.setGmtCreate(now);
                userMapper.insert(newUserDO);
                //这一步是为了封装上数据库上配置的默认值
                userDO = userMapper.selectById(newUserDO.getId());
            } else {
                userDO = userDOS.get(0);
                UserDO userUpdateDO = new UserDO();
                userUpdateDO.setId(userDO.getId());
                userUpdateDO.setGmtLastLogin(new Date());
                userUpdateDO.setLastLoginIp(ip);
                userMapper.updateById(userUpdateDO);
            }
            //检查帐号是否已经冻结
            if (userDO.getStatus() == 0) {
                throw new AppServiceException(ExceptionDefinition.USER_CAN_NOT_ACTICE);
            }
            String accessToken = GeneratorUtil.genSessionId();
            UserDTO userDTO = new UserDTO();
            BeanUtils.copyProperties(userDO, userDTO);
            userRedisTemplate.opsForValue().set(Const.USER_REDIS_PREFIX + accessToken, JSONObject.toJSONString(userDTO));
            userDTO.setAccessToken(accessToken);
            return userDTO;
        } else {
            throw new AppServiceException(ExceptionDefinition.USER_THIRD_UNEXPECT_RESPONSE);
        }
    }

    @Override
    public String syncUserInfo(String nickName, String avatarUrl, Integer gender, Long birthday, String accessToken, Long userId) throws ServiceException {
        UserDO updateUserDO = new UserDO();
        updateUserDO.setId(userId);
        updateUserDO.setNickname(nickName);
        updateUserDO.setAvatarUrl(avatarUrl);
        updateUserDO.setGender(gender);
        updateUserDO.setGmtUpdate(new Date());
        if (birthday != null)
            updateUserDO.setBirthday(new Date(birthday));
        if (userMapper.updateById(updateUserDO) > 0) {
            //更新SESSION缓存
            UserDTO user = SessionUtil.getUser();
            if (!StringUtils.isEmpty(nickName)) {
                user.setNickname(nickName);
            }
            if (!StringUtils.isEmpty(avatarUrl)) {
                user.setAvatarUrl(avatarUrl);
            }
            if (birthday != null) {
                user.setBirthday(new Date(birthday));
            }
            if (gender != null) {
                user.setGender(gender);
            }
            userRedisTemplate.opsForValue().set(Const.USER_REDIS_PREFIX + accessToken, JSONObject.toJSONString(user));
            return "ok";
        }
        throw new AppServiceException(ExceptionDefinition.USER_UNKNOWN_EXCEPTION);
    }


}
