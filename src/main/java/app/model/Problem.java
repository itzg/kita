package app.model;

import java.util.List;

public record Problem(
    String type,
    String detail,
    List<Subproblem> subproblems
) {

}
