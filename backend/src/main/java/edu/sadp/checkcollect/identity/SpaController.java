package edu.sadp.checkcollect.identity;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping({"/", "/login", "/password", "/account", "/admin/users", "/poi", "/poi/**", "/review", "/messages"})
    public String page() { return "forward:/index.html"; }
}
