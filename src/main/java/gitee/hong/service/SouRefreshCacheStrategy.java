package gitee.hong.service;

public interface SouRefreshCacheStrategy {

    /**
     * 缓存刷新的策略，
     * @param key
     * @return true 可以去刷新缓存，false 不可以刷新缓存
     */
    Boolean refreshPermission(String key) throws Exception;
}
