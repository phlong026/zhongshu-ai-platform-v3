package cn.iocoder.yudao.module.franchise.test;

import cn.iocoder.yudao.module.platform.authz.CurrentPost;
import cn.iocoder.yudao.module.platform.org.PostSnapshot;

import java.util.Optional;

/** 测试中手动切换「当前工作身份」 */
public class FixedCurrentPost implements CurrentPost {

    private PostSnapshot post;

    public void use(PostSnapshot post) {
        this.post = post;
    }

    public void clear() {
        this.post = null;
    }

    @Override
    public Optional<PostSnapshot> find() {
        return Optional.ofNullable(post);
    }

}
