package edu.sadp.checkcollect.messaging;

import edu.sadp.checkcollect.identity.Role;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** 预建两个角色群聊；重复启动不会重复创建。 */
@Component
public class MessagingBootstrap implements ApplicationRunner {
    private final ConversationRepository conversations;

    public MessagingBootstrap(ConversationRepository conversations) {
        this.conversations = conversations;
    }

    @Override
    public void run(ApplicationArguments args) {
        conversations.ensureRoleGroup(Role.COLLECTOR, "采集者交流");
        conversations.ensureRoleGroup(Role.REVIEWER, "核验者交流");
    }
}
