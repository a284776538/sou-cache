package gitee.hong.config;

import gitee.hong.aop.SouCacheAspect;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.GenericBeanDefinition;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SouCacheConfiguration implements ApplicationContextInitializer {



    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        DefaultListableBeanFactory configurableListableBeanFactory = (DefaultListableBeanFactory) applicationContext.getBeanFactory();
        GenericBeanDefinition ejpConfigDefinition  = new GenericBeanDefinition();
        ejpConfigDefinition.setBeanClassName("SouCacheAspect");
        ejpConfigDefinition.setBeanClass(SouCacheAspect.class);
        ejpConfigDefinition.setLazyInit(true);
        configurableListableBeanFactory.registerBeanDefinition("SouCacheAspect",ejpConfigDefinition);

    }
}
